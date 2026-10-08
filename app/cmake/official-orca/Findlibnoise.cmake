# Android adaptation for Orca's find_package(libnoise).
#
# Alloy builds the pinned dependency as a source target before adding official
# libslic3r. Orca's desktop Findlibnoise module searches for an installed
# libnoise/noise.h and creates an imported target, which does not match the
# source-tree target's noise.h include layout and would redefine noise::noise.
# Require the already-built source target instead of guessing a library path.
if (TARGET noise::noise)
    set(libnoise_FOUND TRUE)
    set(LIBNOISE_LIBRARY noise::noise)
else ()
    set(libnoise_FOUND FALSE)
    set(libnoise_NOT_FOUND_MESSAGE
        "The pinned source-built noise::noise target must be added before official libslic3r")
endif ()
