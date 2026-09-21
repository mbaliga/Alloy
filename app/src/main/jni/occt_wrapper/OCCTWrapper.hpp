
#ifndef occtwrapper_OCCTWrapper_hpp_
#define occtwrapper_OCCTWrapper_hpp_

#include <array>
#include <string>
#include <vector>

namespace Slic3r {

struct OCCTVolume {
    std::string volume_name;
    std::vector<std::array<float, 3>> vertices;
    std::vector<std::array<int, 3>> indices;
};

struct OCCTResult {
    std::string error_str;
    std::string object_name;
    std::vector<OCCTVolume> volumes;
};

using LoadStepFn = bool (*)(const char *path, OCCTResult* occt_result);

// STEP import is exposed through the same bounded native model boundary as
// STL/OBJ/3MF. The implementation remains in the OCCT wrapper so the Java
// seam does not need to know about Open CASCADE document handles.
extern "C" bool load_step_internal(const char *path, OCCTResult* occt_result);

// Exact solid modeling seam used by the Android workbench. The Java layer
// validates that all paths are app-private cache files before calling this
// function; this native boundary repeats the size and operation checks.
// operation: 0 = fuse, 1 = cut (first minus second), 2 = common.
extern "C" bool boolean_stl_internal(const char *first_path,
                                      const char *second_path,
                                      const char *output_path,
                                      int operation,
                                      std::string& error_str);

}; // namespace Slic3r

#endif // occtwrapper_OCCTWrapper_hpp_
