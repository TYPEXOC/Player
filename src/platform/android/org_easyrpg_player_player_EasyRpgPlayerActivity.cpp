/*
 * This file is part of EasyRPG Player
 *
 * Copyright (c) EasyRPG Project. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

#include "org_easyrpg_player_player_EasyRpgPlayerActivity.h"
#include "android.h"
#include "player.h"
#include "baseui.h"
#include "scene_settings.h"

#ifdef __cplusplus
extern "C" {
#endif

// To prevent race conditions use EpAndroid::schedule for everything that is not just a variable read/write

JNIEXPORT void JNICALL Java_org_easyrpg_player_player_EasyRpgPlayerActivity_endGame(JNIEnv *, jclass) {
	EpAndroid::schedule([]() {
		Player::exit_flag = true;
	});
}

JNIEXPORT void JNICALL Java_org_easyrpg_player_player_EasyRpgPlayerActivity_resetGame(JNIEnv *, jclass) {
	EpAndroid::schedule([]() {
		Player::reset_flag = true;
	});
}

JNIEXPORT void JNICALL Java_org_easyrpg_player_player_EasyRpgPlayerActivity_toggleFps(JNIEnv *, jclass) {
	EpAndroid::schedule([]() {
		if (DisplayUi) {
			DisplayUi->ToggleShowFps();
		}
	});
}

JNIEXPORT void JNICALL
Java_org_easyrpg_player_player_EasyRpgPlayerActivity_openSettings(JNIEnv *, jclass) {
	EpAndroid::schedule([]() {
		if (Scene::instance->type != Scene::Logo && !Scene::Find(Scene::Settings) && !Scene::IsAsyncPending()) {
			Scene::Push(std::make_shared<Scene_Settings>());
		}
	});
}

// Only flips an atomic flag read by the game loop, safe from any thread.
JNIEXPORT void JNICALL
Java_org_easyrpg_player_player_EasyRpgPlayerActivity_requestTranslationReload(JNIEnv *, jclass) {
	Player::translation.RequestExternalReload();
}

// Copies the host-pushed in-memory catalog and queues it; the game loop
// parses and applies it (safe from any thread).
JNIEXPORT void JNICALL
Java_org_easyrpg_player_player_EasyRpgPlayerActivity_setTranslationCatalog(JNIEnv* env, jclass, jbyteArray data) {
	jsize size = env->GetArrayLength(data);
	if (size <= 0) {
		return;
	}
	std::string text;
	text.resize(static_cast<size_t>(size));
	env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte*>(text.data()));
	TyneasyrpgCheatBroker::Instance().PushTranslationCatalog(std::move(text));
}

#ifdef __cplusplus
}
#endif
