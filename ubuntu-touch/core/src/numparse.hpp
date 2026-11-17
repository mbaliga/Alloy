// SPDX-License-Identifier: AGPL-3.0-only
// Locale-independent number parsing (std::from_chars), matching Java parseDouble/parseFloat
// for the decimal text the G-code and STL scanners hand over. Out-of-range values become
// +/-infinity (Java semantics) so the finite checks reject them.
#pragma once
#include <charconv>
#include <cmath>
#include <limits>
#include <optional>
#include <string>
#include <string_view>

namespace alloy::detail {

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
}

}  // namespace alloy::detail
