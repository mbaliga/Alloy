// SPDX-License-Identifier: AGPL-3.0-only
// Locale-independent number parsing (std::from_chars), matching Java parseDouble/parseFloat
// for the decimal text the G-code and STL scanners hand over. Out-of-range values become
// +/-infinity (Java semantics) so the finite checks reject them.
#pragma once
#include <charconv>
#include <cmath>
#include <cerrno>
#include <cstdlib>
#include <locale.h>
#include <limits>
#include <optional>
#include <string>
#include <string_view>
#include <type_traits>

// Floating-point std::from_chars arrived in libstdc++ 11. Clickable's ubuntu-sdk-20.04 containers ship GCC 9, so there
// the same grammar is validated by hand and converted with strtod_l/strtof_l in the "C" locale (never the user's).
// ALLOY_FORCE_STRTOD_PARSE forces this path so it can be tested on a newer compiler.
#if defined(ALLOY_FORCE_STRTOD_PARSE) || !defined(__cpp_lib_to_chars) || __cpp_lib_to_chars < 201611L
#define ALLOY_NUMPARSE_STRTOD 1
#endif

namespace alloy::detail {

#ifdef ALLOY_NUMPARSE_STRTOD
// Accepts what from_chars(chars_format::general) accepts once the sign was removed: digits with an optional '.',
// at least one digit, optional exponent with a signed integer. No hex, no inf/nan words, no whitespace.
inline bool decimalGrammarOk(std::string_view s) {
    std::size_t i = 0, digits = 0;
    while (i < s.size() && s[i] >= '0' && s[i] <= '9') { ++i; ++digits; }
    if (i < s.size() && s[i] == '.') {
        ++i;
        while (i < s.size() && s[i] >= '0' && s[i] <= '9') { ++i; ++digits; }
    }
    if (digits == 0) return false;
    if (i < s.size() && (s[i] == 'e' || s[i] == 'E')) {
        ++i;
        if (i < s.size() && (s[i] == '+' || s[i] == '-')) ++i;
        std::size_t e = 0;
        while (i < s.size() && s[i] >= '0' && s[i] <= '9') { ++i; ++e; }
        if (e == 0) return false;
    }
    return i == s.size();
}

inline locale_t cLocale() {
    static locale_t loc = newlocale(LC_ALL_MASK, "C", static_cast<locale_t>(0));
    return loc;
}

template <typename T>
inline bool fromCharsCompat(std::string_view s, T& value, bool& outOfRange) {
    outOfRange = false;
    if (!decimalGrammarOk(s)) return false;
    std::string buf(s);
    char* end = nullptr;
    errno = 0;
    if constexpr (std::is_same_v<T, float>) value = ::strtof_l(buf.c_str(), &end, cLocale());
    else value = static_cast<T>(::strtod_l(buf.c_str(), &end, cLocale()));
    if (end != buf.c_str() + buf.size()) return false;
    if (errno == ERANGE) outOfRange = true;
    return true;
}
#endif

template <typename T>
inline std::optional<T> parseDecimal(std::string_view s) {
    if (s.empty()) return std::nullopt;
    bool neg = false;
    if (s.front() == '+' || s.front() == '-') { neg = s.front() == '-'; s.remove_prefix(1); }
    // Java accepts a trailing type suffix on parseFloat/parseDouble.
    if (!s.empty() && (s.back() == 'f' || s.back() == 'F' || s.back() == 'd' || s.back() == 'D')) s.remove_suffix(1);
    if (s.empty()) return std::nullopt;
    // Special values: Java accepts "NaN" and "Infinity" (case-sensitive); both are non-finite so callers reject them.
    if (s == "NaN") return std::numeric_limits<T>::quiet_NaN();
    if (s == "Infinity") return neg ? -std::numeric_limits<T>::infinity() : std::numeric_limits<T>::infinity();
    T value{};
#ifdef ALLOY_NUMPARSE_STRTOD
    bool oor = false;
    if (!fromCharsCompat<T>(s, value, oor)) return std::nullopt;
    if (oor) {
        bool underflow = false;
        for (std::size_t i = 0; i + 1 < s.size(); ++i)
            if ((s[i] == 'e' || s[i] == 'E') && s[i + 1] == '-') underflow = true;
        value = underflow ? T(0) : std::numeric_limits<T>::infinity();
    }
    return neg ? -value : value;
#else
    auto r = std::from_chars(s.data(), s.data() + s.size(), value, std::chars_format::general);
    if (r.ec == std::errc::result_out_of_range) {
        bool underflow = false;
        for (std::size_t i = 0; i + 1 < s.size(); ++i)
            if ((s[i] == 'e' || s[i] == 'E') && s[i + 1] == '-') underflow = true;
        value = underflow ? T(0) : std::numeric_limits<T>::infinity();
    } else if (r.ec != std::errc() || r.ptr != s.data() + s.size()) {
        return std::nullopt;
    }
    return neg ? -value : value;
#endif
}

}  // namespace alloy::detail
