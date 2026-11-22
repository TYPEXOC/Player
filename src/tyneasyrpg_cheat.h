/*
 * This file is part of EasyRPG Player.
 *
 * EasyRPG Player is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This file implements the engine-side executor of the TYN cheat protocol:
 * the TYN runtime module writes tab-separated commands into
 * tyranor_cheat_cmd.txt inside the module cache directory, this broker
 * polls them on the game thread, executes them against the engine state and
 * answers into tyranor_cheat_reply.txt. The command vocabulary, reply format
 * and state file are shared with the TYN RGSS module so the same cheat
 * controller and WebView panel work across engines.
 */

#ifndef EP_TYNEASYRPG_CHEAT_H
#define EP_TYNEASYRPG_CHEAT_H

#include <map>
#include <mutex>
#include <string>

class TyneasyrpgCheatBroker {
public:
	static TyneasyrpgCheatBroker& Instance();

	/** Cache directory of the TYN module (from --tyn-cheat-dir). */
	void SetDir(std::string dir);

	/** Poll the command file and apply continuous cheats. Game thread only. */
	void Poll();

	/** Queue an in-memory translation catalog (host-pushed, any thread). */
	void PushTranslationCatalog(std::string po_text);

private:
	TyneasyrpgCheatBroker() = default;

	void HandleCommand(const std::string& cmd, const std::string& a, const std::string& b, std::string& reply);
	std::string ListCapabilities();
	std::string ListSystem();
	void WriteState();
	void ApplyContinuous();
	std::string ListItems();
	std::string ListActors();
	std::string ListMaps();
	std::string ListSaves();
	void ApplyItemLocks();

	std::string dir_;
	int frame_counter_ = 0;
	bool unlimited_gold_ = false;
	std::map<int, bool> switch_locks_;
	std::map<int, int> variable_locks_;
	std::map<int, int> item_locks_;

	// Host-pushed translation catalog, awaiting the game thread.
	std::mutex translation_mutex_;
	std::string translation_pending_;
	bool translation_pending_flag_ = false;
};

#endif
