/*
 * This file is part of EasyRPG Player.
 *
 * EasyRPG Player is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 */

#include "tyn_cheat.h"

#include "game_party.h"
#include "game_switches.h"
#include "game_variables.h"
#include "main_data.h"
#include "output.h"

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
}

TynCheatBroker& TynCheatBroker::Instance() {
	static TynCheatBroker broker;
	return broker;
}

void TynCheatBroker::SetDir(std::string dir) {
	dir_ = std::move(dir);
	Output::Debug("TYN cheat layer active in '{}'", dir_);
}

std::string TynCheatBroker::WireText(const std::string& text) {
	std::string out;
	out.reserve(text.size());
	for (char c : text) {
		out.push_back((c == '\t' || c == '\r' || c == '\n') ? ' ' : c);
	}
	return out;
}

void TynCheatBroker::ApplyContinuous() {
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

void TynCheatBroker::Poll() {
	if (dir_.empty()) {
		return;
	}
	if (++frame_counter_ < POLL_FRAMES) {
		return;
	}
	frame_counter_ = 0;

	ApplyContinuous();

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
			Output::Warning("TYN cheat layer: cannot write reply file");
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

void TynCheatBroker::WriteState() {
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

std::string TynCheatBroker::ListCapabilities() {
	// Line protocol: E=engine, T=toggle, V=value, F=scene feature.
	std::string out = "E\teasyrpg";
	out += "\nT\tgold";
	out += "\nV\tgold";
	return out;
}

std::string TynCheatBroker::ListSystem() {
	if (!Main_Data::game_switches || !Main_Data::game_variables) {
		return NOT_READY;
	}
	const auto& switches = lcf::Data::system.switches;
	const auto& variables = lcf::Data::system.variables;
	if (switches.empty() && variables.empty()) {
		return "The game's switch and variable tables are unavailable.";
	}

	std::vector<std::string> lines;
	for (int id = 1; id < static_cast<int>(switches.size()); ++id) {
		std::string raw(switches[id].c_str());
		bool named = !TrimCopy(raw).empty();
		std::string name = named ? raw : ("Switch " + std::to_string(id));
		bool value = Main_Data::game_switches->Get(id);
		bool locked = switch_locks_.count(id) > 0;
		lines.push_back("S\t" + std::to_string(id) + "\t" + (value ? "1" : "0") + "\t"
			+ WireText(name) + "\t" + (locked ? "1" : "0") + "\t" + (named ? "1" : "0"));
	}
	for (int id = 1; id < static_cast<int>(variables.size()); ++id) {
		std::string raw(variables[id].c_str());
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

void TynCheatBroker::HandleCommand(const std::string& cmd, const std::string& a, const std::string& b, std::string& reply) {
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
		if (a == "coins" || a == "actors" || a == "maps" || a == "battle" || a == "battle_actors"
			|| a == "saves" || a == "items" || a == "events" || a == "minimap") {
			reply = NOPE;
			return;
		}
		reply = "Unknown cheat command.";
		return;
	}
	reply = "Unknown cheat command.";
}
