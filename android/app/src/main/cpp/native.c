// Puente JNI entre la app Android y el nucleo de mGBA (solo GBA).
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <fcntl.h>

#include <mgba/core/core.h>
#include <mgba/gba/core.h>
#include <mgba-util/audio-buffer.h>
#include <mgba-util/vfs.h>

#define GBA_W 240
#define GBA_H 160

static struct mCore* core = NULL;
static mColor* videoBuf = NULL;
static unsigned long frames = 0;

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
	core->runFrame(core);
	frames++;

	void* dst = (*env)->GetDirectBufferAddress(env, video);
	if (dst) {
		memcpy(dst, videoBuf, GBA_W * GBA_H * sizeof(mColor));
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
}

JNIEXPORT jstring JNICALL
Java_cl_zmremake_Native_info(JNIEnv* env, jclass cls) {
	char out[256];
	if (!core || !videoBuf) {
		return (*env)->NewStringUTF(env, "sin nucleo");
	}
	char title[32] = {0}, code[16] = {0};
	core->getGameTitle(core, title);
	core->getGameCode(core, code);
	unsigned nonwhite = 0, total = 0;
	for (int i = 0; i < GBA_W * GBA_H; i += 97) {
		total++;
		if ((videoBuf[i] & 0x00FFFFFF) != 0x00FFFFFF) nonwhite++;
	}
	snprintf(out, sizeof(out), "%s [%s] cuadros=%lu\npx0=%08X centro=%08X no-blancos=%u/%u",
	         title, code, frames, (unsigned) videoBuf[0], (unsigned) videoBuf[80 * GBA_W + 120], nonwhite, total);
	return (*env)->NewStringUTF(env, out);
}
