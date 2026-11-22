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
static int testMode = 0;
static mColor* dbgBuf = NULL;
static int stats[22];
static char pendPath[512];
static volatile int pendAction = 0;   // 1 = guardar estado, 2 = cargar estado
static volatile int lastResult = 0;
static volatile int paused = 0;
static int forceOne = 0;

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
	struct GBA* gba = core->board;
	uint32_t key[256];
	for (int i = 0; i < 256; i++) {
		key[i] = (uint32_t) M_RGB5_TO_BGR8(gba->video.palette[256 + i]) & 0x00FFFFFF;
	}
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
			stats[bg]++;
			float v = 0.12f + lum * 0.65f;
			out[0] = v * BGT[bg][0]; out[1] = v * BGT[bg][1]; out[2] = v * BGT[bg][2];
		} else {
			uint32_t c = raw & 0x00FFFFFF;
			uint32_t bits = 0;
			for (int k = 0; k < 256; k++) {
				if ((k & 15) && key[k] == c) bits |= 1u << (k >> 4);
			}
			if (bits && !(bits & (bits - 1))) {
				int pal = __builtin_ctz(bits);
				stats[5 + pal]++;
				float v = 0.7f + 0.3f * lum;
				out[0] = ((PALCOL[pal] & 0xFF) / 255.f) * v;
				out[1] = (((PALCOL[pal] >> 8) & 0xFF) / 255.f) * v;
				out[2] = (((PALCOL[pal] >> 16) & 0xFF) / 255.f) * v;
			} else {
				stats[21]++;
				float v = 0.75f + 0.25f * lum;
				out[0] = out[1] = out[2] = v;
			}
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
Java_cl_zmremake_Native_runFrame(JNIEnv* env, jclass cls, jobject video, jshortArray audio) {
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
			if (ok && a == 2) forceOne = 1;
		}
	}
	if (!paused || forceOne) {
		forceOne = 0;
		core->runFrame(core);
		frames++;
		trackScroll();
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
	(*env)->SetIntArrayRegion(env, out, 0, 22, stats);
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
