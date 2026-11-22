/*
 * This file is part of EasyRPG Player.
 *
 * EasyRPG Player is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 */

#include "tyneasyrpg_cheat.h"

#include "game_actors.h"
#include "game_map.h"
#include "game_party.h"
#include "game_player.h"
#include "game_switches.h"
#include "filefinder.h"
#include "game_system.h"
#include "game_variables.h"
#include "main_data.h"
#include "output.h"
#include "scene.h"
#include "scene_load.h"
#include "scene_save.h"
#include "teleport_target.h"

#include <lcf/data.h>

#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <vector>

namespace {
constexpr const char* CMD_NAME = "tyranor_cheat_cmd.txt";
constexpr const char* REPLY_NAME = "tyranor_cheat_reply.txt";
constexpr const char* STATE_NAME = "tyranor_cheat_state.txt";
constexpr const char* NOPE = "This cheat doesn't work in this type of game.";
constexpr const char* NOT_READY = "Start or load a game first.";
constexpr int GOLD_MAX = 999999;
constexpr int POLL_FRAMES = 6;

std::string TrimCopy(const std::string& text) {
	size_t begin = text.find_first_not_of(" \t\r\n");
	if (begin == std::string::npos) return {};
	size_t end = text.find_last_not_of(" \t\r\n");
	return text.substr(begin, end - begin + 1);
}

std::string WireText(const std::string& text) {
	std::string out;
	out.reserve(text.size());
	for (char c : text) {
		out.push_back((c == '\t' || c == '\r' || c == '\n') ? ' ' : c);
	}
	return out;
}
}

TyneasyrpgCheatBroker& TyneasyrpgCheatBroker::Instance() {
	static TyneasyrpgCheatBroker broker;
	return broker;
}

void TyneasyrpgCheatBroker::SetDir(std::string dir) {
	dir_ = std::move(dir);
	Output::Debug("Tyneasyrpg cheat layer active in '{}'", dir_);
}

void TyneasyrpgCheatBroker::ApplyContinuous() {
	if (Main_Data::game_party && unlimited_gold_) {
		int gold = Main_Data::game_party->GetGold();
		if (gold < GOLD_MAX) {
			Main_Data::game_party->GainGold(GOLD_MAX - gold);
		}
	}
	if (Main_Data::game_variables) {
		for (auto& lock : variable_locks_) {
			Main_Data::game_variables->Set(lock.first, lock.second);
		}
	}
	if (Main_Data::game_switches) {
		for (auto& lock : switch_locks_) {
			Main_Data::game_switches->Set(lock.first, lock.second);
		}
	}
}

void TyneasyrpgCheatBroker::ApplyItemLocks() {
	if (!Main_Data::game_party) {
		return;
	}
	for (auto& lock : item_locks_) {
		int count = Main_Data::game_party->GetItemCount(lock.first);
		if (count != lock.second) {
			Main_Data::game_party->AddItem(lock.first, lock.second - count);
		}
	}
}

void TyneasyrpgCheatBroker::Poll() {
	if (dir_.empty()) {
		return;
	}
	if (++frame_counter_ < POLL_FRAMES) {
		return;
	}
	frame_counter_ = 0;

	ApplyContinuous();
	ApplyItemLocks();

	std::error_code ec;
	std::filesystem::path base(dir_);
	std::filesystem::path cmd_path = base / CMD_NAME;
	if (!std::filesystem::exists(cmd_path, ec) || ec) {
		return;
	}

	std::ifstream in(cmd_path, std::ios::binary);
	if (!in) {
		return;
	}
	std::string line((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
	in.close();
	std::filesystem::remove(cmd_path, ec);
	if (line.empty()) {
		return;
	}
	// Protocol: <id>\t<cmd>\t<a>\t<b>
	size_t p1 = line.find('\t');
	if (p1 == std::string::npos) {
		return;
	}
	std::string id = line.substr(0, p1);
	std::string rest = line.substr(p1 + 1);
	std::string cmd, a, b;
	size_t p2 = rest.find('\t');
	if (p2 == std::string::npos) {
		cmd = rest;
	} else {
		cmd = rest.substr(0, p2);
		rest = rest.substr(p2 + 1);
		p2 = rest.find('\t');
		if (p2 == std::string::npos) {
			a = rest;
		} else {
			a = rest.substr(0, p2);
			b = rest.substr(p2 + 1);
		}
	}

	std::string reply;
	HandleCommand(cmd, a, b, reply);

	std::filesystem::path reply_path = base / REPLY_NAME;
	std::filesystem::path tmp_path = base / (std::string(REPLY_NAME) + ".tmp");
	{
		std::ofstream out(tmp_path, std::ios::binary | std::ios::trunc);
		if (!out) {
			Output::Warning("Tyneasyrpg cheat layer: cannot write reply file");
			return;
		}
		out << id << "\t" << reply;
	}
	std::filesystem::rename(tmp_path, reply_path, ec);
	if (ec) {
		// Fall back to a plain move (rename across filesystems etc.).
		std::filesystem::remove(reply_path, ec);
		std::filesystem::rename(tmp_path, reply_path, ec);
	}
}

void TyneasyrpgCheatBroker::WriteState() {
	if (dir_.empty()) {
		return;
	}
	std::error_code ec;
	std::filesystem::path base(dir_);
	std::filesystem::path state_path = base / STATE_NAME;
	std::filesystem::path tmp_path = base / (std::string(STATE_NAME) + ".tmp");
	{
		std::ofstream out(tmp_path, std::ios::binary | std::ios::trunc);
		// Format shared with the RGSS executor: speed, toggles, exp, drop, game speed, ev.
		std::string toggles = unlimited_gold_ ? "gold" : "";
		out << "1.0\t" << toggles << "\t1.0\t1.0\t1\t1.0";
	}
	std::filesystem::rename(tmp_path, state_path, ec);
	if (ec) {
		std::filesystem::remove(state_path, ec);
		std::filesystem::rename(tmp_path, state_path, ec);
	}
}

std::string TyneasyrpgCheatBroker::ListCapabilities() {
	// Line protocol: E=engine, T=toggle, V=value, AS=actor stat field, F=scene feature.
	std::string out = "E\teasyrpg";
	out += "\nT\tgold";
	out += "\nV\tgold";
	for (const char* field : {"level", "exp", "hp", "mhp", "mp", "mmp", "atk", "def", "spi", "agi"}) {
		out += "\nAS\t";
		out += field;
	}
	return out;
}

std::string TyneasyrpgCheatBroker::ListItems() {
	std::vector<std::string> lines;
	for (const auto& item : lcf::Data::items) {
		if (item.ID <= 0) {
			continue;
		}
		std::string raw(static_cast<std::string>(item.name));
		if (TrimCopy(raw).empty()) {
			continue;
		}
		std::string desc(static_cast<std::string>(item.description));
		int count = Main_Data::game_party ? Main_Data::game_party->GetItemCount(item.ID) : 0;
		bool locked = item_locks_.count(item.ID) > 0;
		lines.push_back("item\t" + std::to_string(item.ID) + "\t" + std::to_string(count)
			+ "\t" + WireText(raw) + "\t" + WireText(desc) + "\t" + (locked ? "1" : "0") + "\t");
	}
	std::string out;
	for (size_t i = 0; i < lines.size(); ++i) {
		if (i > 0) out += "\n";
		out += lines[i];
	}
	return out;
}

std::string TyneasyrpgCheatBroker::ListActors() {
	if (!Main_Data::game_actors || !Main_Data::game_party) {
		return NOT_READY;
	}
	std::vector<std::string> lines;
	for (const auto& db_actor : lcf::Data::actors) {
		int id = db_actor.ID;
		if (id <= 0) {
			continue;
		}
		Game_Actor* actor = Main_Data::game_actors->GetActor(id);
		if (!actor) {
			continue;
		}
		std::string raw(static_cast<std::string>(db_actor.name));
		std::string name = TrimCopy(raw).empty() ? ("Actor " + std::to_string(id)) : raw;
		bool in_party = Main_Data::game_party->IsActorInParty(id);
		std::string line = "A\t" + std::to_string(id) + "\t" + WireText(name)
			+ "\t" + std::to_string(actor->GetLevel())
			+ "\t" + std::to_string(actor->GetHp())
			+ "\t" + std::to_string(actor->GetMaxHp())
			+ "\t" + std::to_string(actor->GetSp())
			+ "\t" + std::to_string(actor->GetMaxSp())
			+ "\t" + (in_party ? "1" : "0")
			+ "\texp=" + std::to_string(actor->GetExp())
			+ "\tatk=" + std::to_string(actor->GetAtk())
			+ "\tdef=" + std::to_string(actor->GetDef())
			+ "\tspi=" + std::to_string(actor->GetSpi())
			+ "\tagi=" + std::to_string(actor->GetAgi());
		lines.push_back(line);
	}
	std::string out;
	for (size_t i = 0; i < lines.size(); ++i) {
		if (i > 0) out += "\n";
		out += lines[i];
	}
	return out;
}

std::string TyneasyrpgCheatBroker::ListMaps() {
	if (!Main_Data::game_player) {
		return NOT_READY;
	}
	std::vector<std::string> lines;
	int current_map = Game_Map::GetMapId();
	for (const auto& info : lcf::Data::treemap.maps) {
		if (info.ID <= 0 || info.type != lcf::rpg::TreeMap::MapType_map) {
			continue;
		}
		std::string raw(static_cast<std::string>(info.name));
		std::string name = TrimCopy(raw).empty() ? ("Map " + std::to_string(info.ID)) : raw;
		bool here = (info.ID == current_map);
		std::string x = here ? std::to_string(Main_Data::game_player->GetX()) : "";
		std::string y = here ? std::to_string(Main_Data::game_player->GetY()) : "";
		std::string dir = here ? std::to_string(Main_Data::game_player->GetDirection()) : "";
		lines.push_back(std::to_string(info.ID) + "\t" + (here ? "1" : "0")
			+ "\t" + std::to_string(info.indentation)
			+ "\t" + x + "\t" + y + "\t" + dir + "\t" + WireText(name));
	}
	std::string out;
	for (size_t i = 0; i < lines.size(); ++i) {
		if (i > 0) out += "\n";
		out += lines[i];
	}
	return out;
}

std::string TyneasyrpgCheatBroker::ListSaves() {
	FilesystemView save_fs = FileFinder::Save();
	if (!save_fs) {
		return NOT_READY;
	}
	std::vector<std::string> lines;
	for (int id = 1; id <= 20; ++id) {
		std::string name = "Save" + (id < 10 ? std::string("0") : "") + std::to_string(id) + ".lsd";
		bool exists = save_fs.IsFile(name);
		lines.push_back("S\t" + std::to_string(id) + "\t" + (exists ? "1" : "0")
			+ "\t0\t\t" + "Save " + std::to_string(id));
	}
	std::string out;
	for (size_t i = 0; i < lines.size(); ++i) {
		if (i > 0) out += "\n";
		out += lines[i];
	}
	return out;
}

std::string TyneasyrpgCheatBroker::ListSystem() {
	if (!Main_Data::game_switches || !Main_Data::game_variables) {
		return NOT_READY;
	}
	// Switch/variable names live in the LDB database (lcf::Data), not in
	// rpg::System; entries carry their own 1-based ID.
	const auto& switches = lcf::Data::switches;
	const auto& variables = lcf::Data::variables;
	if (switches.empty() && variables.empty()) {
		return "The game's switch and variable tables are unavailable.";
	}

	std::vector<std::string> lines;
	for (const auto& sw : switches) {
		int id = sw.ID;
		if (id <= 0) {
			continue;
		}
		std::string raw(static_cast<std::string>(sw.name));
		bool named = !TrimCopy(raw).empty();
		std::string name = named ? raw : ("Switch " + std::to_string(id));
		bool value = Main_Data::game_switches->Get(id);
		bool locked = switch_locks_.count(id) > 0;
		lines.push_back("S\t" + std::to_string(id) + "\t" + (value ? "1" : "0") + "\t"
			+ WireText(name) + "\t" + (locked ? "1" : "0") + "\t" + (named ? "1" : "0"));
	}
	for (const auto& var : variables) {
		int id = var.ID;
		if (id <= 0) {
			continue;
		}
		std::string raw(static_cast<std::string>(var.name));
		bool named = !TrimCopy(raw).empty();
		std::string name = named ? raw : ("Variable " + std::to_string(id));
		int32_t value = Main_Data::game_variables->Get(id);
		bool locked = variable_locks_.count(id) > 0;
		lines.push_back("V\t" + std::to_string(id) + "\tnumber\t" + std::to_string(value)
			+ "\t" + WireText(name) + "\t" + (locked ? "1" : "0") + "\t" + (named ? "1" : "0"));
	}
	// Join without a trailing newline (shared controller splits on \n).
	std::string out;
	for (size_t i = 0; i < lines.size(); ++i) {
		if (i > 0) out += "\n";
		out += lines[i];
	}
	return out;
}

void TyneasyrpgCheatBroker::HandleCommand(const std::string& cmd, const std::string& a, const std::string& b, std::string& reply) {
	if (cmd == "restore") {
		unlimited_gold_ = false;
		switch_locks_.clear();
		variable_locks_.clear();
		WriteState();
		return;
	}
	if (cmd == "toggle") {
		if (a == "gold") {
			unlimited_gold_ = (b == "1");
			WriteState();
			return;
		}
		reply = NOPE;
		return;
	}
	if (cmd == "value") {
		if (a == "gold") {
			if (!Main_Data::game_party) {
				reply = NOT_READY;
				return;
			}
			errno = 0;
			char* end = nullptr;
			long target = std::strtol(TrimCopy(b).c_str(), &end, 10);
			if (errno != 0 || end == nullptr || *end != '\0' || end == b.c_str()) {
				reply = "Enter a number.";
				return;
			}
			int current = Main_Data::game_party->GetGold();
			int delta = static_cast<int>(std::min<long>(target, GOLD_MAX)) - current;
			if (delta > 0) {
				Main_Data::game_party->GainGold(delta);
			} else if (delta < 0) {
				Main_Data::game_party->LoseGold(-delta);
			}
			reply = "Gold set to " + std::to_string(Main_Data::game_party->GetGold());
			return;
		}
		reply = NOPE;
		return;
	}
	if (cmd == "system_switch") {
		if (!Main_Data::game_switches) {
			reply = NOT_READY;
			return;
		}
		int id = std::atoi(a.c_str());
		Main_Data::game_switches->Set(id, b == "1");
		return;
	}
	if (cmd == "system_variable") {
		if (!Main_Data::game_variables) {
			reply = NOT_READY;
			return;
		}
		int id = std::atoi(a.c_str());
		errno = 0;
		char* end = nullptr;
		long value = std::strtol(TrimCopy(b).c_str(), &end, 10);
		if (errno != 0 || end == nullptr || end == b.c_str()) {
			return;
		}
		Main_Data::game_variables->Set(id, static_cast<int32_t>(value));
		return;
	}
	if (cmd == "system_lock") {
		if (!Main_Data::game_switches || !Main_Data::game_variables) {
			reply = NOT_READY;
			return;
		}
		// Protocol: a = kind ("switch"/"variable"), b = "<id>\t<0|1>".
		size_t sep = b.find('\t');
		if (sep == std::string::npos) {
			reply = "Unknown cheat command.";
			return;
		}
		int id = std::atoi(TrimCopy(b.substr(0, sep)).c_str());
		bool enable = (TrimCopy(b.substr(sep + 1)) == "1");
		if (a == "switch") {
			if (enable) {
				switch_locks_[id] = Main_Data::game_switches->Get(id);
			} else {
				switch_locks_.erase(id);
			}
			WriteState();
			return;
		}
		if (a == "variable") {
			if (enable) {
				variable_locks_[id] = Main_Data::game_variables->Get(id);
			} else {
				variable_locks_.erase(id);
			}
			WriteState();
			return;
		}
		reply = NOPE;
		return;
	}
	if (cmd == "give") {
		// Protocol: a = kind ("item"), b = "<id>\t<count>".
		if (a == "item") {
			if (!Main_Data::game_party) {
				reply = NOT_READY;
				return;
			}
			size_t sep = b.find('\t');
			if (sep == std::string::npos) {
				reply = "Unknown cheat command.";
				return;
			}
			int id = std::atoi(TrimCopy(b.substr(0, sep)).c_str());
			int amount = std::atoi(TrimCopy(b.substr(sep + 1)).c_str());
			if (id > 0 && amount != 0) {
				Main_Data::game_party->AddItem(id, amount);
				Game_Map::SetNeedRefresh(true);
			}
			reply = "OK";
			return;
		}
		reply = NOPE;
		return;
	}
	if (cmd == "item_lock") {
		if (!Main_Data::game_party) {
			reply = NOT_READY;
			return;
		}
		// Protocol: a = kind ("item"), b = "<id>\t<0|1>".
		size_t sep = b.find('\t');
		if (a == "item" && sep != std::string::npos) {
			int id = std::atoi(TrimCopy(b.substr(0, sep)).c_str());
			bool enable = (TrimCopy(b.substr(sep + 1)) == "1");
			if (enable) {
				item_locks_[id] = Main_Data::game_party->GetItemCount(id);
			} else {
				item_locks_.erase(id);
			}
			return;
		}
		reply = NOPE;
		return;
	}
	if (cmd == "actor_value") {
		if (!Main_Data::game_actors || !Main_Data::game_party) {
			reply = NOT_READY;
			return;
		}
		// Protocol: a = actor id, b = "<field>\t<value>".
		size_t sep = b.find('\t');
		if (sep == std::string::npos) {
			reply = "Unknown cheat command.";
			return;
		}
		int id = std::atoi(a.c_str());
		std::string field = TrimCopy(b.substr(0, sep));
		long value = std::strtol(TrimCopy(b.substr(sep + 1)).c_str(), nullptr, 10);
		Game_Actor* actor = Main_Data::game_actors->GetActor(id);
		if (!actor) {
			reply = NOT_READY;
			return;
		}
		if (field == "level") {
			actor->ChangeLevel(static_cast<int>(value), nullptr);
		} else if (field == "exp") {
			actor->SetExp(static_cast<int>(value));
		} else if (field == "hp") {
			actor->SetHp(static_cast<int>(value));
		} else if (field == "mp") {
			actor->SetSp(static_cast<int>(value));
		} else if (field == "atk") {
			actor->SetBaseAtk(static_cast<int>(value));
		} else if (field == "def") {
			actor->SetBaseDef(static_cast<int>(value));
		} else if (field == "spi") {
			actor->SetBaseSpi(static_cast<int>(value));
		} else if (field == "agi") {
			actor->SetBaseAgi(static_cast<int>(value));
		} else {
			reply = NOPE;
			return;
		}
		reply = "OK";
		return;
	}
	if (cmd == "actor_recover") {
		if (!Main_Data::game_actors || !Main_Data::game_party) {
			reply = NOT_READY;
			return;
		}
		Game_Actor* actor = Main_Data::game_actors->GetActor(std::atoi(a.c_str()));
		if (actor) {
			actor->FullHeal();
		}
		reply = "OK";
		return;
	}
	if (cmd == "actor_party") {
		if (!Main_Data::game_party) {
			reply = NOT_READY;
			return;
		}
		int id = std::atoi(a.c_str());
		if (b == "1") {
			Main_Data::game_party->AddActor(id);
		} else {
			Main_Data::game_party->RemoveActor(id);
		}
		reply = "OK";
		return;
	}
	if (cmd == "delete_save") {
		reply = "Not supported yet.";
		return;
	}
	if (cmd == "run") {
		if (a == "maxGold") {
			if (!Main_Data::game_party) {
				reply = NOT_READY;
				return;
			}
			int delta = GOLD_MAX - Main_Data::game_party->GetGold();
			if (delta > 0) {
				Main_Data::game_party->GainGold(delta);
			}
			reply = "Gold set to " + std::to_string(Main_Data::game_party->GetGold());
			return;
		}
		if (a == "recoverParty") {
			if (!Main_Data::game_party || !Main_Data::game_system) {
				reply = NOT_READY;
				return;
			}
			Main_Data::game_system->SePlay(Main_Data::game_system->GetSystemSE(Main_Data::game_system->SFX_UseItem));
			for (auto& actor : Main_Data::game_party->GetActors()) {
				actor->FullHeal();
			}
			reply = "Party fully recovered.";
			return;
		}
		if (a == "saveScreen" || a == "loadScreen"
			|| a.rfind("saveSlot:", 0) == 0 || a.rfind("loadSlot:", 0) == 0) {
			if (!Main_Data::game_party || !Scene::instance) {
				reply = NOT_READY;
				return;
			}
			if (a == "saveScreen" || a.rfind("saveSlot:", 0) == 0) {
				Scene::Push(std::make_shared<Scene_Save>());
				reply = "Opened the save screen.";
			} else {
				Scene::Push(std::make_shared<Scene_Load>());
				reply = "Opened the load screen.";
			}
			return;
		}
		if (a.rfind("warp:", 0) == 0) {
			if (!Main_Data::game_player || !Scene::instance
				|| Scene::instance->type != Scene::Map) {
				reply = "Teleport is only available on the map.";
				return;
			}
			int map_id = std::atoi(a.substr(5).c_str());
			// b = "<x>\t<y>" (also accept a comma separator).
			std::string coords = b;
			std::replace(coords.begin(), coords.end(), ',', '\t');
			size_t sep = coords.find('\t');
			int x = sep == std::string::npos ? 0 : std::atoi(TrimCopy(coords.substr(0, sep)).c_str());
			int y = sep == std::string::npos ? 0 : std::atoi(TrimCopy(coords.substr(sep + 1)).c_str());
			Main_Data::game_player->ReserveTeleport(map_id, x, y, -1, TeleportTarget::eSkillTeleport);
			reply = "Warping to map " + std::to_string(map_id) + ".";
			return;
		}
		reply = "Unknown action.";
		return;
	}
	if (cmd == "list") {
		if (a == "capabilities") {
			reply = ListCapabilities();
			return;
		}
		if (a == "gold") {
			int gold = Main_Data::game_party ? Main_Data::game_party->GetGold() : 0;
			reply = "G\t" + std::to_string(gold);
			return;
		}
		if (a == "system") {
			reply = ListSystem();
			return;
		}
		if (a.empty()) {
			reply = ListItems();
			return;
		}
		if (a == "actors") {
			reply = ListActors();
			return;
		}
		if (a == "maps") {
			reply = ListMaps();
			return;
		}
		if (a == "saves") {
			reply = ListSaves();
			return;
		}
		if (a == "coins" || a == "battle" || a == "battle_actors"
			|| a == "items" || a == "events" || a == "minimap") {
			reply = NOPE;
			return;
		}
		reply = "Unknown cheat command.";
		return;
	}
	reply = "Unknown cheat command.";
}
