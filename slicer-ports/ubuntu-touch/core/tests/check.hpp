// SPDX-License-Identifier: AGPL-3.0-only
// Minimal dependency-free test harness (no gtest, so the core builds on a bare toolchain).
#pragma once
#include <cstdio>
#include <string>

inline int g_checks = 0, g_failures = 0;
#define CHECK(cond) do { ++g_checks; if (!(cond)) { ++g_failures; std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); } } while (0)
#define CHECK_MSG(cond, msg) do { ++g_checks; if (!(cond)) { ++g_failures; std::fprintf(stderr, "FAIL %s:%d: %s -- %s\n", __FILE__, __LINE__, #cond, std::string(msg).c_str()); } } while (0)
#define CHECK_EQ(a, b) do { ++g_checks; if (!((a) == (b))) { ++g_failures; std::fprintf(stderr, "FAIL %s:%d: %s == %s\n", __FILE__, __LINE__, #a, #b); } } while (0)
inline bool contains(const std::string& s, const char* needle) { return s.find(needle) != std::string::npos; }
