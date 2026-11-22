// Puente JNI entre la app Android y el nucleo de mGBA (solo GBA).
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <fcntl.h>

#include <mgba/core/core.h>
#include <mgba/gba/core.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/io.h>
#include <mgba/core/serialize.h>
#include <mgba-util/image.h>
#include <mgba-util/audio-buffer.h>
#include <mgba-util/vfs.h>

#define GBA_W 240
#define GBA_H 160

static struct mCore* core = NULL;
static mColor* videoBuf = NULL;
static unsigned long frames = 0;

extern uint8_t zmFlagBuf[];
extern uint8_t zmPalBuf[];
extern uint8_t zmNzBuf[];
static int testMode = 0;
static mColor* dbgBuf = NULL;
static int stats[38];
static char pendPath[512];
static volatile int pendAction = 0;   // 1 = guardar estado, 2 = cargar estado
static volatile int lastResult = 0;
static volatile int paused = 0;
static int forceOne = 0;

// Retroceso: instantaneas en memoria cada 6 cuadros (anillo de 24 = unos 2,4 s)
#define RW_N 24
#define RW_EVERY 6
#define RW_BACK 20   // 20 instantaneas x 6 cuadros = 2 s
static void* rwBuf[RW_N];
static int rwHead = 0, rwCount = 0, rwTick = 0;
static volatile int rwReq = 0;
static volatile int cheatLife = 0;
static int lastEn = -1, lastMis = -1, lastSup = -1, lastPb = -1;
static volatile int haloPal = 0xFFFF;   // paletas de sprite que reciben halo (bit = numero de paleta)

static void rwSnap(void) {
	if (!rwBuf[rwHead]) {
		rwBuf[rwHead] = malloc(core->stateSize(core));
		if (!rwBuf[rwHead]) return;
	}
	if (core->saveState(core, rwBuf[rwHead])) {
		rwHead = (rwHead + 1) % RW_N;
		if (rwCount < RW_N) rwCount++;
	}
}

static void rwBack(void) {
	if (rwCount <= 0) return;
	int steps = rwCount < RW_BACK ? rwCount : RW_BACK;
	int idx = (rwHead - steps + RW_N) % RW_N;
	if (core->loadState(core, rwBuf[idx])) {
		lastEn = lastMis = lastSup = lastPb = -1;
		rwHead = (idx + 1) % RW_N;
		rwCount = rwCount - steps + 1;
		forceOne = 1;
	}
}

#define RGBC(r, g, b) ((mColor) ((r) | ((g) << 8) | ((b) << 16) | (0xFFu << 24)))
static const mColor PALCOL[16] = {
	RGBC(230, 25, 75), RGBC(60, 180, 75), RGBC(255, 225, 25), RGBC(0, 130, 200),
	RGBC(245, 130, 48), RGBC(145, 30, 180), RGBC(70, 240, 240), RGBC(240, 50, 230),
	RGBC(210, 245, 60), RGBC(250, 190, 190), RGBC(0, 128, 128), RGBC(170, 110, 40),
	RGBC(128, 0, 0), RGBC(170, 255, 195), RGBC(0, 0, 128), RGBC(128, 128, 128)
};
static const float BGT[5][3] = {
	{0.45f, 0.65f, 1.00f}, {0.45f, 1.00f, 0.55f}, {1.00f, 0.55f, 0.45f}, {1.00f, 0.95f, 0.45f}, {0.35f, 0.35f, 0.35f}
};

// Pinta la imagen de diagnostico: fondo en gris con tinte por capa, sprites por paleta.
static void buildDebug(void) {
	memset(stats, 0, sizeof(stats));
	for (int i = 0; i < GBA_W * GBA_H; i++) {
		uint32_t raw = videoBuf[i];
		float r = (raw & 0xFF) / 255.f, g = ((raw >> 8) & 0xFF) / 255.f, b = ((raw >> 16) & 0xFF) / 255.f;
		float lum = 0.3f * r + 0.59f * g + 0.11f * b;
		uint8_t f = zmFlagBuf[i];
		float out[3];
		if ((f & 0xFC) == 0xFC) {
			stats[4]++;
			float v = 0.08f + lum * 0.25f;
			out[0] = out[1] = out[2] = v;
		} else if (f & 0x08) {
			int bg = (f >> 4) & 3;
			int bk = zmPalBuf[i] & 15;
			stats[bg]++;
			stats[22 + bk]++;
			float v = 0.10f + lum * 0.55f;
			out[0] = v * ((PALCOL[bk] & 0xFF) / 255.f);
			out[1] = v * (((PALCOL[bk] >> 8) & 0xFF) / 255.f);
			out[2] = v * (((PALCOL[bk] >> 16) & 0xFF) / 255.f);
		} else {
			int pal = zmPalBuf[i] & 15;
			stats[5 + pal]++;
			float v = 0.7f + 0.3f * lum;
			out[0] = ((PALCOL[pal] & 0xFF) / 255.f) * v;
			out[1] = (((PALCOL[pal] >> 8) & 0xFF) / 255.f) * v;
			out[2] = (((PALCOL[pal] >> 16) & 0xFF) / 255.f) * v;
		}
		int R = (int) (out[0] * 255.f), G = (int) (out[1] * 255.f), B = (int) (out[2] * 255.f);
		if (R > 255) R = 255; if (G > 255) G = 255; if (B > 255) B = 255;
		dbgBuf[i] = RGBC(R, G, B);
	}
}

// Seguimiento del scroll de las 4 capas de fondo (acumulado, sin saltos por el limite de 512)
static int accX[4], accY[4], prevX[4], prevY[4];
static float activity[4];
static int curBg = 2;
static int havePrev = 0;

static void trackScroll(void) {
	struct GBA* gba = core->board;
	uint16_t dispcnt = gba->memory.io[GBA_REG(DISPCNT)];
	for (int b = 0; b < 4; b++) {
		int x = gba->memory.io[GBA_REG(BG0HOFS) + 2 * b] & 0x1FF;
		int y = gba->memory.io[GBA_REG(BG0VOFS) + 2 * b] & 0x1FF;
		if (havePrev) {
			int dx = ((x - prevX[b] + 256) & 511) - 256;
			int dy = ((y - prevY[b] + 256) & 511) - 256;
			accX[b] += dx;
			accY[b] += dy;
			float a = (float) ((dx < 0 ? -dx : dx) + (dy < 0 ? -dy : dy));
			activity[b] = activity[b] * 0.97f + ((dispcnt & (0x100 << b)) ? a : 0.f);
		}
		prevX[b] = x;
		prevY[b] = y;
	}
	havePrev = 1;
	int best = curBg;
	for (int b = 0; b < 4; b++) {
		if (activity[b] > activity[best] * 1.5f + 1.f) best = b;
	}
	curBg = best;
}


JNIEXPORT jboolean JNICALL
Java_cl_zmremake_Native_open(JNIEnv* env, jclass cls, jstring romPath, jstring savePath) {
	if (core) {
		return JNI_FALSE;
	}
	const char* rom = (*env)->GetStringUTFChars(env, romPath, NULL);
	const char* sav = (*env)->GetStringUTFChars(env, savePath, NULL);

	core = GBACoreCreate();
	if (!core) {
		goto fail;
	}
	if (!core->init(core)) {
		core = NULL;
		goto fail;
	}
	mCoreInitConfig(core, "zmremake");

	videoBuf = malloc(GBA_W * GBA_H * sizeof(mColor));
	dbgBuf = malloc(GBA_W * GBA_H * sizeof(mColor));
	core->setVideoBuffer(core, videoBuf, GBA_W);
	core->setAudioBufferSize(core, 1024);

	struct VFile* vf = VFileOpen(rom, O_RDONLY);
	if (!vf || !core->loadROM(core, vf)) {
		goto fail_core;
	}
	struct VFile* sv = VFileOpen(sav, O_CREAT | O_RDWR);
	if (sv) {
		core->loadSave(core, sv);
	}
	core->reset(core);

	(*env)->ReleaseStringUTFChars(env, romPath, rom);
	(*env)->ReleaseStringUTFChars(env, savePath, sav);
	return JNI_TRUE;

fail_core:
	core->deinit(core);
	core = NULL;
	for (int i = 0; i < RW_N; i++) { free(rwBuf[i]); rwBuf[i] = NULL; }
	rwHead = rwCount = rwTick = 0;
	free(videoBuf);
	videoBuf = NULL;
fail:
	(*env)->ReleaseStringUTFChars(env, romPath, rom);
	(*env)->ReleaseStringUTFChars(env, savePath, sav);
	return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_cl_zmremake_Native_audioRate(JNIEnv* env, jclass cls) {
	return core ? (jint) core->audioSampleRate(core) : 32768;
}

JNIEXPORT void JNICALL
Java_cl_zmremake_Native_setKeys(JNIEnv* env, jclass cls, jint keys) {
	if (core) {
		core->setKeys(core, (uint32_t) keys);
	}
}

// Avanza un cuadro. Copia el video (RGBA 8888) al buffer directo y el audio
// (estereo, int16) al arreglo. Devuelve cuantos pares de muestras hay.
JNIEXPORT jint JNICALL
Java_cl_zmremake_Native_runFrame(JNIEnv* env, jclass cls, jobject video, jobject mask, jshortArray audio) {
	if (!core) {
		return 0;
	}
	if (pendAction) {
		int a = pendAction;
		pendAction = 0;
		struct VFile* vf = VFileOpen(pendPath, a == 1 ? (O_CREAT | O_TRUNC | O_RDWR) : O_RDONLY);
		lastResult = -1;
		if (vf) {
			bool ok = a == 1 ? mCoreSaveStateNamed(core, vf, SAVESTATE_SAVEDATA)
			                 : mCoreLoadStateNamed(core, vf, SAVESTATE_SAVEDATA);
			vf->close(vf);
			lastResult = ok ? 1 : -1;
			if (ok && a == 2) { forceOne = 1; rwCount = 0; lastEn = lastMis = lastSup = lastPb = -1; }
		}
	}
	if (rwReq) {
		rwReq = 0;
		rwBack();
	}
	if (!paused || forceOne) {
		int wasForced = forceOne;
		forceOne = 0;
		core->runFrame(core);
		frames++;
		trackScroll();
		if (cheatLife) {
			// energia actual en 0x03001536, maxima en 0x03001530 (16 bits). Si baja, se restaura.
			int cur = core->busRead16(core, 0x03001536);
			int mx = core->busRead16(core, 0x03001530);
			if (mx > 0 && cur <= mx) {
				if (lastEn >= 0 && cur < lastEn) {
					core->busWrite16(core, 0x03001536, (uint16_t) lastEn);
				} else {
					lastEn = cur;
				}
			}
			// municion: misiles (16 bits, 0x03001538), super misiles (8 bits, 0x0300153A), bombas de poder (8 bits, 0x0300153B)
			int mm = core->busRead16(core, 0x03001532);
			int mc = core->busRead16(core, 0x03001538);
			if (mm > 0 && mc <= mm) {
				if (lastMis >= 0 && mc < lastMis) core->busWrite16(core, 0x03001538, (uint16_t) lastMis); else lastMis = mc;
			}
			int sm = core->busRead8(core, 0x03001534), sc = core->busRead8(core, 0x0300153A);
			if (sm > 0 && sc <= sm) {
				if (lastSup >= 0 && sc < lastSup) core->busWrite8(core, 0x0300153A, (uint8_t) lastSup); else lastSup = sc;
			}
			int pm = core->busRead8(core, 0x03001535), pc = core->busRead8(core, 0x0300153B);
			if (pm > 0 && pc <= pm) {
				if (lastPb >= 0 && pc < lastPb) core->busWrite8(core, 0x0300153B, (uint8_t) lastPb); else lastPb = pc;
			}
		} else {
			lastEn = lastMis = lastSup = lastPb = -1;
		}
		if (!wasForced && ++rwTick >= RW_EVERY) {
			rwTick = 0;
			rwSnap();
		}
	}

	void* dst = (*env)->GetDirectBufferAddress(env, video);
	if (dst) {
		if (testMode) {
			buildDebug();
			memcpy(dst, dbgBuf, GBA_W * GBA_H * sizeof(mColor));
		} else {
			memcpy(dst, videoBuf, GBA_W * GBA_H * sizeof(mColor));
		}
	}

	uint8_t* mdst = (*env)->GetDirectBufferAddress(env, mask);
	if (mdst) {
		// Cuenta de pixeles por paleta de sprite (para distinguir pantalla de juego, menu y cabina)
		int cnt[16] = {0};
		for (int i = 0; i < GBA_W * GBA_H; i++) {
			uint8_t f = zmFlagBuf[i];
			if (((f & 0xFC) != 0xFC) && !(f & 0x08)) cnt[zmPalBuf[i] & 15]++;
		}
		int menu = (cnt[0] == 0 && cnt[5] > 0);   // sin Samus pero con interfaz: pantalla de mapa/pausa
		for (int i = 0; i < GBA_W * GBA_H; i++) {
			uint8_t f = zmFlagBuf[i];
			int x = i % GBA_W, y = i / GBA_W;
			uint8_t m = 0;
			uint8_t* sp = mdst + GBA_W * GBA_H + 2 * i;   // (ruido, tipo): tipo 255 = Samus, 128 = otro sprite, 0 = fondo
			sp[0] = 0; sp[1] = 0;
			if (((f & 0xFC) != 0xFC) && !(f & 0x08)) {
				int pal = zmPalBuf[i] & 15;
				int hud = (x < 100 && y < 24);   // texto y numeros de vida: nunca brillan
				sp[0] = zmNzBuf[i];
				sp[1] = (pal == 0 && !hud) ? 255 : 128;
				if (!hud) {
					// 255 = halo normal, 128 = halo HUD (menu y mapa), 192 = halo normal forzado (cabina)
					if (menu) m = 128;
					else if (pal == 2 || pal == 3 || pal == 4 || pal == 6) m = 255;
					else if (pal == 5 && x >= 205 && y < 28) m = 128;   // mapa de la esquina
				}
			} else if ((f & 0x08) && ((f >> 4) & 3) == 1 && (zmPalBuf[i] & 15) == 1 && !menu) {
				m = 160;   // puertas: paleta 1 del fondo; el filtro de color deja fuera el metal gris
			} else if ((f & 0x08) && ((f >> 4) & 3) == 0 && !menu) {
				// lava: pixeles rojo-naranja muy saturados y claros en la capa BG0
				uint32_t raw = videoBuf[i];
				float r = (raw & 0xFF) / 255.f, g = ((raw >> 8) & 0xFF) / 255.f, b = ((raw >> 16) & 0xFF) / 255.f;
				float mx = r > g ? (r > b ? r : b) : (g > b ? g : b);
				float mn = r < g ? (r < b ? r : b) : (g < b ? g : b);
				if (mx > 0.65f && mx - mn > 0.65f * mx && r >= mx - 0.001f && g < 0.75f * r && b < 0.45f * r) m = 255;
			}
			mdst[i] = m;
		}
	}

	jsize cap = (*env)->GetArrayLength(env, audio) / 2;
	struct mAudioBuffer* ab = core->getAudioBuffer(core);
	int16_t tmp[4096];
	size_t want = (size_t) cap < 2048 ? (size_t) cap : 2048;
	size_t got = mAudioBufferRead(ab, tmp, want);
	(*env)->SetShortArrayRegion(env, audio, 0, (jsize) (got * 2), tmp);
	return (jint) got;
}

JNIEXPORT void JNICALL
Java_cl_zmremake_Native_close(JNIEnv* env, jclass cls) {
	if (core) {
		core->deinit(core);
		core = NULL;
	}
	free(videoBuf);
	videoBuf = NULL;
	free(dbgBuf);
	dbgBuf = NULL;
}

JNIEXPORT jstring JNICALL
Java_cl_zmremake_Native_info(JNIEnv* env, jclass cls) {
	char out[256];
	if (!core || !videoBuf) {
		return (*env)->NewStringUTF(env, "sin nucleo");
	}
	struct mGameInfo gi;
	memset(&gi, 0, sizeof(gi));
	core->getGameInfo(core, &gi);
	const char* title = gi.title;
	const char* code = gi.code;
	unsigned nonwhite = 0, total = 0;
	for (int i = 0; i < GBA_W * GBA_H; i += 97) {
		total++;
		if ((videoBuf[i] & 0x00FFFFFF) != 0x00FFFFFF) nonwhite++;
	}
	snprintf(out, sizeof(out), "%s [%s] cuadros=%lu\npx0=%08X centro=%08X no-blancos=%u/%u",
	         title, code, frames, (unsigned) videoBuf[0], (unsigned) videoBuf[80 * GBA_W + 120], nonwhite, total);
	return (*env)->NewStringUTF(env, out);
}

JNIEXPORT jint JNICALL
Java_cl_zmremake_Native_scrollX(JNIEnv* env, jclass cls) { return accX[curBg]; }

JNIEXPORT jint JNICALL
Java_cl_zmremake_Native_scrollY(JNIEnv* env, jclass cls) { return accY[curBg]; }

JNIEXPORT jint JNICALL
Java_cl_zmremake_Native_scrollBg(JNIEnv* env, jclass cls) { return curBg; }

JNIEXPORT void JNICALL
Java_cl_zmremake_Native_setTest(JNIEnv* env, jclass cls, jboolean on) { testMode = on ? 1 : 0; }

JNIEXPORT void JNICALL
Java_cl_zmremake_Native_testStats(JNIEnv* env, jclass cls, jintArray out) {
	(*env)->SetIntArrayRegion(env, out, 0, 38, stats);
}

JNIEXPORT void JNICALL
Java_cl_zmremake_Native_requestState(JNIEnv* env, jclass cls, jstring path, jint action) {
	const char* p = (*env)->GetStringUTFChars(env, path, NULL);
	strncpy(pendPath, p, sizeof(pendPath) - 1);
	pendPath[sizeof(pendPath) - 1] = 0;
	(*env)->ReleaseStringUTFChars(env, path, p);
	lastResult = 0;
	pendAction = action;
}

JNIEXPORT jint JNICALL
Java_cl_zmremake_Native_stateResult(JNIEnv* env, jclass cls) {
	int r = lastResult;
	lastResult = 0;
	return r;
}

JNIEXPORT void JNICALL
Java_cl_zmremake_Native_setPaused(JNIEnv* env, jclass cls, jboolean on) { paused = on ? 1 : 0; }

JNIEXPORT void JNICALL
Java_cl_zmremake_Native_rewind(JNIEnv* env, jclass cls) { rwReq = 1; }

JNIEXPORT void JNICALL
Java_cl_zmremake_Native_setHaloPal(JNIEnv* env, jclass cls, jint mask) { haloPal = mask & 0xFFFF; }

JNIEXPORT void JNICALL
Java_cl_zmremake_Native_setCheatLife(JNIEnv* env, jclass cls, jboolean on) { cheatLife = on ? 1 : 0; lastEn = lastMis = lastSup = lastPb = -1; }
