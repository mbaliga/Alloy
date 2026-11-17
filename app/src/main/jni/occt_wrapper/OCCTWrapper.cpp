#include "OCCTWrapper.hpp"

#include "occtwrapper_export.h"

#include <cassert>

#ifdef _WIN32
#define DIR_SEPARATOR '\\'
#else
#define DIR_SEPARATOR '/'
#endif

#include "STEPCAFControl_Reader.hxx"
#include "STEPControl_Reader.hxx"
#include "BRepMesh_IncrementalMesh.hxx"
#include "XCAFDoc_DocumentTool.hxx"
#include "XCAFDoc_ShapeTool.hxx"
#include "XCAFApp_Application.hxx"
#include "TopoDS_Builder.hxx"
#include "TopoDS.hxx"
#include "NCollection_Sequence.hxx"
#include "TDataStd_Name.hxx"
#include "BRepBuilderAPI_Transform.hxx"
#include "BRepBuilderAPI_Sewing.hxx"
#include "BRepBuilderAPI_MakeSolid.hxx"
#include "BRepBuilderAPI_MakeShapeOnMesh.hxx"
#include "BRepCheck_Analyzer.hxx"
#include "BRepAlgoAPI_Fuse.hxx"
#include "BRepAlgoAPI_Cut.hxx"
#include "BRepAlgoAPI_Common.hxx"
#include "TopExp_Explorer.hxx"
#include "BRep_Tool.hxx"
#include "TopAbs_ShapeEnum.hxx"
#include "TopoDS_Face.hxx"
#include "Poly_Triangulation.hxx"
#include "Poly_Triangle.hxx"

#include <sys/stat.h>

#include <fstream>
#include <cerrno>
#include <cstring>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <algorithm>

const double STEP_TRANS_CHORD_ERROR = 0.005;
const double STEP_TRANS_ANGLE_RES = 1;

// Boolean operations are intentionally smaller than the general model import
// limit. STL-to-BRep conversion temporarily holds both the faceted input and
// a topological copy, so allowing arbitrarily large files would make a phone
// process vulnerable to avoidable memory pressure.
const off_t BOOLEAN_MAX_INPUT_BYTES = 32 * 1024 * 1024;
const off_t BOOLEAN_MAX_OUTPUT_BYTES = 64 * 1024 * 1024;
const size_t BOOLEAN_MAX_PATH_CHARS = 4096;
const uint32_t BOOLEAN_MAX_TRIANGLES = 200000;
const double BOOLEAN_MAX_COORDINATE_MM = 10000.0;

// const int LOAD_STEP_STAGE_READ_FILE          = 0;
// const int LOAD_STEP_STAGE_GET_SOLID          = 1;
// const int LOAD_STEP_STAGE_GET_MESH           = 2;

namespace Slic3r {

struct NamedSolid {
    NamedSolid(const TopoDS_Shape& s,
               const std::string& n) : solid{s}, name{n} {}
    const TopoDS_Shape solid;
    const std::string  name;
};

static void getNamedSolids(const TopLoc_Location& location, const Handle(XCAFDoc_ShapeTool) shapeTool,
                           const TDF_Label label, std::vector<NamedSolid>& namedSolids)
{
    TDF_Label referredLabel{label};
    if (shapeTool->IsReference(label))
        shapeTool->GetReferredShape(label, referredLabel);

    std::string name;
    Handle(TDataStd_Name) shapeName;
    if (referredLabel.FindAttribute(TDataStd_Name::GetID(), shapeName))
        name = TCollection_AsciiString(shapeName->Get()).ToCString();

    TopLoc_Location localLocation = location * shapeTool->GetLocation(label);
    NCollection_Sequence<TDF_Label> components;
    // XCAF can report that a label participates in the component API while
    // returning an empty sequence for a single-part STEP product. Treat that
    // as a leaf and inspect its shape; otherwise valid single-body CAD files
    // are accepted but tessellate to an empty Alloy 3MF.
    if (shapeTool->GetComponents(referredLabel, components) && components.Length() > 0) {
        for (Standard_Integer compIndex = 1; compIndex <= components.Length(); ++compIndex) {
            getNamedSolids(localLocation, shapeTool, components.Value(compIndex), namedSolids);
        }
    } else {
        TopoDS_Shape shape;
        shapeTool->GetShape(referredLabel, shape);
        TopAbs_ShapeEnum shape_type = shape.ShapeType();
        BRepBuilderAPI_Transform transform(shape, localLocation, Standard_True);
        switch (shape_type) {
        case TopAbs_COMPOUND:
        case TopAbs_COMPSOLID:
            // A positioned STEP product is commonly exposed as one compound
            // containing several printable solids. Treating that compound as
            // one volume makes the Android Parts inspector lose the assembly
            // structure even though tessellation succeeds. Split only at
            // this leaf boundary; component transforms have already been
            // applied to the transformed shape above.
            {
                std::vector<TopoDS_Solid> solids;
                for (TopExp_Explorer explorer(transform.Shape(), TopAbs_SOLID);
                     explorer.More(); explorer.Next()) {
                    const TopoDS_Shape& current = explorer.Current();
                    if (!current.IsNull())
                        solids.push_back(TopoDS::Solid(current));
                }
                if (solids.size() <= 1) {
                    if (solids.size() == 1 && !solids.front().IsNull())
                        namedSolids.emplace_back(solids.front(), name);
                    break;
                }
                for (size_t index = 0; index < solids.size(); ++index) {
                    if (solids[index].IsNull())
                        continue;
                    const std::string suffix = name.empty()
                            ? std::string("solid-") + std::to_string(index + 1)
                            : name + " · solid-" + std::to_string(index + 1);
                    namedSolids.emplace_back(solids[index], suffix);
                }
            }
            break;
        case TopAbs_SOLID:
            if (!transform.Shape().IsNull())
                namedSolids.emplace_back(TopoDS::Solid(transform.Shape()), name);
            break;
        default:
            break;
        }
    }
}

static bool appendTriangulatedSolid(const NamedSolid& named, OCCTResult* res)
{
    res->volumes.emplace_back();
    auto& vertices = res->volumes.back().vertices;
    auto& indices  = res->volumes.back().indices;

    BRepMesh_IncrementalMesh mesh(named.solid, STEP_TRANS_CHORD_ERROR, false, STEP_TRANS_ANGLE_RES, true);

    for (TopExp_Explorer anExpSF(named.solid, TopAbs_FACE); anExpSF.More(); anExpSF.Next()) {
        const int aNodeOffset = int(vertices.size());
        const TopoDS_Shape& aFace = anExpSF.Current();
        TopLoc_Location aLoc;
        Handle(Poly_Triangulation) aTriangulation = BRep_Tool::Triangulation(TopoDS::Face(aFace), aLoc);
        if (aTriangulation.IsNull())
            continue;

        gp_Trsf aTrsf = aLoc.Transformation();
        for (Standard_Integer aNodeIter = 1; aNodeIter <= aTriangulation->NbNodes(); ++aNodeIter) {
            gp_Pnt aPnt = aTriangulation->Node(aNodeIter);
            aPnt.Transform(aTrsf);
            vertices.push_back({float(aPnt.X()), float(aPnt.Y()), float(aPnt.Z())});
        }
        const TopAbs_Orientation anOrientation = anExpSF.Current().Orientation();
        for (Standard_Integer aTriIter = 1; aTriIter <= aTriangulation->NbTriangles(); ++aTriIter) {
            Poly_Triangle aTri = aTriangulation->Triangle(aTriIter);
            Standard_Integer anId[3];
            aTri.Get(anId[0], anId[1], anId[2]);
            if (anOrientation == TopAbs_REVERSED)
                std::swap(anId[1], anId[2]);
            indices.push_back({anId[0] - 1 + aNodeOffset,
                               anId[1] - 1 + aNodeOffset,
                               anId[2] - 1 + aNodeOffset});
        }
    }

    res->volumes.back().volume_name = named.name;
    if (vertices.empty()) {
        res->volumes.pop_back();
        return false;
    }
    return true;
}

extern "C" OCCTWRAPPER_EXPORT bool load_step_internal(const char *path, OCCTResult* res /*BBS:, ImportStepProgressFn proFn*/)
{
try {
    res->volumes.clear();
    res->error_str.clear();
    // STEPControl's direct product shape is the most stable mobile import
    // boundary. Some XCAF documents expose transient or null component
    // labels for otherwise valid assemblies; traversing those labels can
    // crash inside OCCT's handle copy before the fallback can report an
    // error. Read the positioned product shape first and only use XCAF when
    // the direct reader cannot expose a tessellatable solid.
    const char *early_last_slash = strrchr(path, DIR_SEPARATOR);
    res->object_name = (early_last_slash == nullptr) ? path : early_last_slash + 1;
    {
        STEPControl_Reader directReader;
        IFSelect_ReturnStatus directStatus = directReader.ReadFile(path);
        if (directStatus == IFSelect_RetDone && directReader.TransferRoots() > 0) {
            TopoDS_Shape directShape = directReader.OneShape();
            std::vector<TopoDS_Solid> directSolids;
            if (!directShape.IsNull()) {
                for (TopExp_Explorer explorer(directShape, TopAbs_SOLID);
                     explorer.More(); explorer.Next()) {
                    const TopoDS_Shape& current = explorer.Current();
                    if (!current.IsNull())
                        directSolids.push_back(TopoDS::Solid(current));
                }
            }
            for (size_t index = 0; index < directSolids.size(); ++index) {
                if (directSolids[index].IsNull())
                    continue;
                appendTriangulatedSolid(
                        NamedSolid(directSolids[index],
                                std::string("solid-") + std::to_string(index + 1)), res);
            }
            if (!res->volumes.empty())
                return true;
        }
    }
    //bool cb_cancel = false;
    //if (proFn) {
    //    proFn(LOAD_STEP_STAGE_READ_FILE, 0, 1, cb_cancel);
    //    if (cb_cancel)
    //        return false;
    //}
    

    std::vector<NamedSolid> namedSolids;
    Handle(TDocStd_Document) document;
    Handle(XCAFApp_Application) application = XCAFApp_Application::GetApplication();
    application->NewDocument(path, document);
    STEPCAFControl_Reader reader;
    reader.SetNameMode(true);
    //BBS: Todo, read file is slow which cause the progress_bar no update and gui no response
    IFSelect_ReturnStatus stat = reader.ReadFile(path);
    if (stat != IFSelect_RetDone || !reader.Transfer(document)) {
        application->Close(document);
        res->error_str = std::string{"Could not read '"} + path + "'";
        return false;
    }
    Handle(XCAFDoc_ShapeTool) shapeTool = XCAFDoc_DocumentTool::ShapeTool(document->Main());
    NCollection_Sequence<TDF_Label> topLevelShapes;
    shapeTool->GetFreeShapes(topLevelShapes);

    Standard_Integer topShapeLength = topLevelShapes.Length() + 1;
    for (Standard_Integer iLabel = 1; iLabel < topShapeLength; ++iLabel) {
        //if (proFn) {
        //    proFn(LOAD_STEP_STAGE_GET_SOLID, iLabel, topShapeLength, cb_cancel);
        //    if (cb_cancel) {
        //        shapeTool.reset(nullptr);
        //        application->Close(document);
        //        return false;
        //    }
        //}
        getNamedSolids(TopLoc_Location{}, shapeTool, topLevelShapes.Value(iLabel), namedSolids);
    }

    

    // Now the object name. Set it to filename without suffix.
    // This will later be changed if only one volume is loaded.
    const char *last_slash = strrchr(path, DIR_SEPARATOR);
    std::string obj_name((last_slash == nullptr) ? path : last_slash + 1);
    res->object_name = obj_name;

    for (size_t i = 0; i < namedSolids.size(); ++i) {
        //BBS:if (proFn) {
        //    proFn(LOAD_STEP_STAGE_GET_MESH, i, namedSolids.size(), cb_cancel);
        //    if (cb_cancel) {
        //        model->delete_object(new_object);
        //        shapeTool.reset(nullptr);
        //        application->Close(document);
        //        return false;
        //    }
        //}

        appendTriangulatedSolid(namedSolids[i], res);
    }

    shapeTool.reset(nullptr);
    application->Close(document);

    // STEPControl exposes some CAD assemblies more faithfully than the XCAF
    // component labels: the latter can collapse an assembly into one
    // compound/volume even when the Part 21 file contains several positioned
    // MANIFOLD_SOLID_BREP products. Use the direct shape as a structural
    // cross-check and prefer its individual solids when it reveals more than
    // the XCAF path. This keeps the geometry and placements while restoring
    // the part boundary needed by the phone inspector.
    if (namedSolids.size() <= 1) {
        STEPControl_Reader directReader;
        IFSelect_ReturnStatus directStatus = directReader.ReadFile(path);
        if (directStatus == IFSelect_RetDone && directReader.TransferRoots() > 0) {
            TopoDS_Shape directShape = directReader.OneShape();
            std::vector<TopoDS_Solid> directSolids;
            for (TopExp_Explorer explorer(directShape, TopAbs_SOLID);
                 explorer.More(); explorer.Next()) {
                const TopoDS_Shape& current = explorer.Current();
                if (!current.IsNull())
                    directSolids.push_back(TopoDS::Solid(current));
            }
            if (directSolids.size() > namedSolids.size()) {
                res->volumes.clear();
                namedSolids.clear();
                for (size_t index = 0; index < directSolids.size(); ++index) {
                    namedSolids.emplace_back(directSolids[index],
                            std::string("solid-") + std::to_string(index + 1));
                    appendTriangulatedSolid(namedSolids.back(), res);
                }
            }
        }
    }

    // Some Open CASCADE STEP products have a valid XCAF document but expose
    // no usable free-shape triangulation through the shape-tool hierarchy.
    // Retry through STEPControl's direct product shape before failing. This
    // preserves the named/multi-part XCAF path while making ordinary single
    // solids reliably renderable on Android.
    if (res->volumes.empty()) {
        STEPControl_Reader directReader;
        IFSelect_ReturnStatus directStatus = directReader.ReadFile(path);
        if (directStatus == IFSelect_RetDone && directReader.TransferRoots() > 0) {
            TopoDS_Shape directShape = directReader.OneShape();
            if (!directShape.IsNull())
                appendTriangulatedSolid(NamedSolid(directShape, ""), res);
        }
    }

    if (res->volumes.empty()) {
        res->error_str = std::string{"STEP contained no tessellatable solids: "} + path;
        return false;
    }
} catch (const std::exception& ex) {
    res->error_str = ex.what();
    return false;
} catch (...) {
    res->error_str = "An exception was thrown in load_step_internal.";
    return false;
}
    
    return true;
}

static bool boolean_file_is_bounded(const char *path, const bool output, std::string& error)
{
    if (path == nullptr || path[0] == '\0' || std::strlen(path) > BOOLEAN_MAX_PATH_CHARS) {
        error = "Boolean model path is invalid";
        return false;
    }
    struct stat info {};
    if (::stat(path, &info) != 0) {
        // An output file may not exist yet. The parent and final path are
        // checked by the Java cache boundary; native code only needs to avoid
        // treating a pre-existing non-regular result as a writable artifact.
        if (output && errno == ENOENT)
            return true;
        error = output ? "Boolean output path is not accessible" : "Boolean input model is not accessible";
        return false;
    }
    if (!S_ISREG(info.st_mode)) {
        error = output ? "Boolean output is not a regular file" : "Boolean input is not a regular file";
        return false;
    }
    const off_t limit = output ? BOOLEAN_MAX_OUTPUT_BYTES : BOOLEAN_MAX_INPUT_BYTES;
    if (info.st_size <= 0 || info.st_size > limit) {
        error = output ? "Boolean output exceeds the 64 MB limit" : "Boolean input exceeds the 32 MB limit";
        return false;
    }
    return true;
}

static bool read_binary_stl_mesh(const char *path,
                                 occ::handle<Poly_Triangulation>& mesh,
                                 std::string& error)
{
    if (!boolean_file_is_bounded(path, false, error))
        return false;

    struct stat info {};
    if (::stat(path, &info) != 0 || info.st_size < 84) {
        error = "Boolean input STL is truncated";
        return false;
    }
    std::ifstream input(path, std::ios::binary);
    if (!input.is_open()) {
        error = "Boolean input STL could not be opened";
        return false;
    }
    char header[80];
    unsigned char count_bytes[4];
    if (!input.read(header, sizeof(header)).read(reinterpret_cast<char *>(count_bytes), sizeof(count_bytes))) {
        error = "Boolean input STL header is truncated";
        return false;
    }
    uint32_t triangle_count = static_cast<uint32_t>(count_bytes[0])
            | (static_cast<uint32_t>(count_bytes[1]) << 8)
            | (static_cast<uint32_t>(count_bytes[2]) << 16)
            | (static_cast<uint32_t>(count_bytes[3]) << 24);
    if (triangle_count == 0 || triangle_count > BOOLEAN_MAX_TRIANGLES
            || 84ULL + 50ULL * triangle_count > static_cast<uint64_t>(info.st_size)) {
        error = "Boolean input STL has an unsupported triangle count";
        return false;
    }
    mesh = new Poly_Triangulation(static_cast<int>(triangle_count * 3),
                                  static_cast<int>(triangle_count), false);
    unsigned char record[50];
    for (uint32_t index = 0; index < triangle_count; ++index) {
        if (!input.read(reinterpret_cast<char *>(record), sizeof(record))) {
            error = "Boolean input STL triangle data is truncated";
            return false;
        }
        for (int vertex = 0; vertex < 3; ++vertex) {
            float coordinates[3];
            std::memcpy(&coordinates[0], record + 12 + vertex * 12, sizeof(float));
            std::memcpy(&coordinates[1], record + 16 + vertex * 12, sizeof(float));
            std::memcpy(&coordinates[2], record + 20 + vertex * 12, sizeof(float));
            if (!std::isfinite(coordinates[0]) || !std::isfinite(coordinates[1])
                    || !std::isfinite(coordinates[2])
                    || std::abs(coordinates[0]) > BOOLEAN_MAX_COORDINATE_MM
                    || std::abs(coordinates[1]) > BOOLEAN_MAX_COORDINATE_MM
                    || std::abs(coordinates[2]) > BOOLEAN_MAX_COORDINATE_MM) {
                error = "Boolean input STL contains invalid coordinates";
                return false;
            }
            mesh->SetNode(static_cast<int>(index * 3 + vertex + 1),
                          gp_Pnt(coordinates[0], coordinates[1], coordinates[2]));
        }
        mesh->SetTriangle(static_cast<int>(index + 1),
                          Poly_Triangle(static_cast<int>(index * 3 + 1),
                                        static_cast<int>(index * 3 + 2),
                                        static_cast<int>(index * 3 + 3)));
    }
    return true;
}

static bool stl_to_solid(const char *path, TopoDS_Shape& solid, std::string& error)
{
    occ::handle<Poly_Triangulation> mesh;
    if (!read_binary_stl_mesh(path, mesh, error))
        return false;

    BRepBuilderAPI_MakeShapeOnMesh converter(mesh);
    converter.Build();
    TopoDS_Shape facets = converter.Shape();
    if (!converter.IsDone() || facets.IsNull()) {
        error = "Boolean input STL could not be converted to facets";
        return false;
    }

    // STL has no topology. Reconnect coincident facet edges before building a
    // solid, then reject open/non-manifold meshes instead of sending an
    // ambiguous shell to the boolean kernel.
    BRepBuilderAPI_Sewing sewing(1.0e-4, true, true, true, false);
    sewing.Add(facets);
    sewing.Perform();
    const TopoDS_Shape& sewed = sewing.SewedShape();
    if (sewed.IsNull()) {
        error = "Boolean input STL did not form a connected shell";
        return false;
    }

    TopoDS_Shell shell;
    if (sewed.ShapeType() == TopAbs_SHELL) {
        shell = TopoDS::Shell(sewed);
    } else {
        int shell_count = 0;
        for (TopExp_Explorer explorer(sewed, TopAbs_SHELL); explorer.More(); explorer.Next()) {
            shell = TopoDS::Shell(explorer.Current());
            ++shell_count;
            if (shell_count > 1) break;
        }
        if (shell_count != 1) {
            error = "Boolean currently requires one watertight solid per input";
            return false;
        }
    }

    BRepBuilderAPI_MakeSolid maker(shell);
    if (!maker.IsDone()) {
        error = "Boolean input shell could not be converted to a solid";
        return false;
    }
    solid = maker.Solid();
    BRepCheck_Analyzer analyzer(solid, true);
    if (!analyzer.IsValid()) {
        error = "Boolean input solid is invalid or non-manifold";
        return false;
    }
    return true;
}

static bool result_has_solid(const TopoDS_Shape& shape)
{
    if (shape.IsNull()) return false;
    int solids = 0;
    for (TopExp_Explorer explorer(shape, TopAbs_SOLID); explorer.More(); explorer.Next()) {
        if (++solids > 0) return true;
    }
    return false;
}

static void write_float(std::ofstream& output, const float value)
{
    output.write(reinterpret_cast<const char *>(&value), sizeof(value));
}

static void write_uint32(std::ofstream& output, const uint32_t value)
{
    unsigned char bytes[4] = {static_cast<unsigned char>(value & 0xff),
                               static_cast<unsigned char>((value >> 8) & 0xff),
                               static_cast<unsigned char>((value >> 16) & 0xff),
                               static_cast<unsigned char>((value >> 24) & 0xff)};
    output.write(reinterpret_cast<const char *>(bytes), sizeof(bytes));
}

static bool write_binary_stl(const TopoDS_Shape& shape, const char *path, std::string& error)
{
    uint64_t triangle_count = 0;
    for (TopExp_Explorer explorer(shape, TopAbs_FACE); explorer.More(); explorer.Next()) {
        TopLoc_Location location;
        occ::handle<Poly_Triangulation> triangulation =
                BRep_Tool::Triangulation(TopoDS::Face(explorer.Current()), location);
        if (triangulation.IsNull()) continue;
        triangle_count += static_cast<uint64_t>(triangulation->NbTriangles());
        if (triangle_count > BOOLEAN_MAX_TRIANGLES * 5ULL) {
            error = "Boolean result is too detailed to export on this device";
            return false;
        }
    }
    if (triangle_count == 0 || 84ULL + 50ULL * triangle_count > BOOLEAN_MAX_OUTPUT_BYTES) {
        error = "Boolean result exceeds the output limit";
        return false;
    }
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output.is_open()) {
        error = "Boolean output STL could not be opened";
        return false;
    }
    char header[80] = {};
    const char title[] = "Alloy OCCT boolean result";
    std::memcpy(header, title, (std::min)(sizeof(header), sizeof(title) - 1));
    output.write(header, sizeof(header));
    write_uint32(output, static_cast<uint32_t>(triangle_count));
    for (TopExp_Explorer explorer(shape, TopAbs_FACE); explorer.More(); explorer.Next()) {
        TopLoc_Location location;
        occ::handle<Poly_Triangulation> triangulation =
                BRep_Tool::Triangulation(TopoDS::Face(explorer.Current()), location);
        if (triangulation.IsNull()) continue;
        const gp_Trsf transform = location.Transformation();
        const bool reversed = explorer.Current().Orientation() == TopAbs_REVERSED;
        for (int index = 1; index <= triangulation->NbTriangles(); ++index) {
            Poly_Triangle triangle = triangulation->Triangle(index);
            int ids[3]; triangle.Get(ids[0], ids[1], ids[2]);
            if (reversed) std::swap(ids[1], ids[2]);
            gp_Pnt points[3] = {triangulation->Node(ids[0]), triangulation->Node(ids[1]), triangulation->Node(ids[2])};
            for (gp_Pnt& point : points) point.Transform(transform);
            const gp_Vec first(points[0], points[1]);
            const gp_Vec second(points[0], points[2]);
            gp_Vec normal = first.Crossed(second);
            const double length = normal.Magnitude();
            if (length > 0.0) normal /= length;
            write_float(output, static_cast<float>(normal.X()));
            write_float(output, static_cast<float>(normal.Y()));
            write_float(output, static_cast<float>(normal.Z()));
            for (const gp_Pnt& point : points) {
                write_float(output, static_cast<float>(point.X()));
                write_float(output, static_cast<float>(point.Y()));
                write_float(output, static_cast<float>(point.Z()));
            }
            unsigned char attributes[2] = {0, 0};
            output.write(reinterpret_cast<const char *>(attributes), sizeof(attributes));
        }
    }
    output.flush();
    if (!output.good()) {
        error = "Boolean output STL could not be written";
        return false;
    }
    return true;
}

extern "C" OCCTWRAPPER_EXPORT bool boolean_stl_internal(const char *first_path,
                                                          const char *second_path,
                                                          const char *output_path,
                                                          int operation,
                                                          std::string& error_str)
{
    try {
        if (operation < 0 || operation > 2) {
            error_str = "Boolean operation is unsupported";
            return false;
        }
        if (output_path == nullptr || output_path[0] == '\0') {
            error_str = "Boolean output path is missing";
            return false;
        }
        if (!boolean_file_is_bounded(first_path, false, error_str)
                || !boolean_file_is_bounded(second_path, false, error_str))
            return false;

        TopoDS_Shape first;
        TopoDS_Shape second;
        if (!stl_to_solid(first_path, first, error_str)
                || !stl_to_solid(second_path, second, error_str))
            return false;

        TopoDS_Shape result;
        if (operation == 0) {
            BRepAlgoAPI_Fuse boolean(first, second);
            if (boolean.HasErrors()) {
                error_str = "OCCT union failed";
                return false;
            }
            result = boolean.Shape();
        } else if (operation == 1) {
            BRepAlgoAPI_Cut boolean(first, second);
            if (boolean.HasErrors()) {
                error_str = "OCCT subtraction failed";
                return false;
            }
            result = boolean.Shape();
        } else {
            BRepAlgoAPI_Common boolean(first, second);
            if (boolean.HasErrors()) {
                error_str = "OCCT intersection failed";
                return false;
            }
            result = boolean.Shape();
        }
        if (!result_has_solid(result)) {
            error_str = "Boolean operation produced an empty or non-solid result";
            return false;
        }
        BRepCheck_Analyzer analyzer(result, true);
        if (!analyzer.IsValid()) {
            error_str = "OCCT produced an invalid boolean result";
            return false;
        }

        // The boolean result may contain newly generated faces without a
        // triangulation. Generate a bounded, deterministic display/export
        // mesh before writing the binary STL consumed by Alloy's normal model
        // cache and slicer path.
        BRepMesh_IncrementalMesh mesh(result, 0.01, false, 0.5, false);
        if (!write_binary_stl(result, output_path, error_str)) {
            return false;
        }
        if (!boolean_file_is_bounded(output_path, true, error_str)) {
            std::remove(output_path);
            return false;
        }
        return true;
    } catch (const std::exception& ex) {
        error_str = ex.what();
        return false;
    } catch (...) {
        error_str = "An exception was thrown during the boolean operation";
        return false;
    }
}

}; // namespace Slic3r
