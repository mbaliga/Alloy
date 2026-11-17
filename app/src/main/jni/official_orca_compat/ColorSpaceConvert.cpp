// Android-only extraction of OrcaSlicer's RGB2HSV routine. The adjacent
// desktop ColorSpaceConvert.cpp also depends on wxWidgets color dialogs; the
// libslic3r flush-volume code uses only this conversion.
#include <algorithm>
#include <cmath>

void RGB2HSV(float r, float g, float b, float* h, float* s, float* v)
{
    const float cmax = std::max(std::max(r, g), b);
    const float cmin = std::min(std::min(r, g), b);
    const float delta = cmax - cmin;

    if (std::abs(delta) < 0.001f) {
        *h = 0.f;
    } else if (cmax == r) {
        *h = 60.f * std::fmod((g - b) / delta, 6.f);
    } else if (cmax == g) {
        *h = 60.f * ((b - r) / delta + 2.f);
    } else {
        *h = 60.f * ((r - g) / delta + 4.f);
    }

    *s = std::abs(cmax) < 0.001f ? 0.f : delta / cmax;
    *v = cmax;
}
