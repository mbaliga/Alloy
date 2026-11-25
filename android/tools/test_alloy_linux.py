from __future__ import annotations

from pathlib import Path
import hashlib
import json
import sys
import tempfile
import unittest
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.alloy_linux import (AlloyError, build_engine_command, inspect_model, inspect_project, slice_model,
                               validate_batch_archive, validate_gcode_package)

def write_minimal_artifact(path: Path) -> bytes:
    gcode = b"G90\nM83\nM104 S0\n"
    with ZipFile(path, "w") as archive:
        archive.writestr("[Content_Types].xml", "3dmodel.model Metadata/plate_1.gcode application/vnd.ms-package.3dmanufacturing-3dmodel+xml")
        archive.writestr("_rels/.rels", "3dmodel /3D/3dmodel.model")
        archive.writestr("3D/_rels/3dmodel.model.rels", "<Relationships></Relationships>")
        archive.writestr("3D/3dmodel.model", "<model><resources><object><mesh><vertices><triangles></triangles></vertices></mesh></object></resources><build><item/></build></model>")
        archive.writestr("Metadata/plate_1.json", '{"engine_verified":false,"printer":"A1 mini","material":"PLA","layer_height_mm":0.2,"first_layer_height_mm":0.2,"infill_percent":15,"perimeters":2,"top_layers":4,"bottom_layers":4,"supports":false,"support_threshold_degrees":45,"nozzle_temperature_c":210,"first_layer_nozzle_temperature_c":215,"bed_temperature_c":55,"first_layer_bed_temperature_c":55,"extrusion_multiplier":1,"max_volumetric_speed_mm3_s":12}')
        archive.writestr("Metadata/slice_info.config", "[print]\nengine_verified=false\nlayer_height_mm=0.2\nfirst_layer_height_mm=0.2\ninfill_percent=15\nperimeters=2\ntop_layers=4\nbottom_layers=4\nsupports=false\nsupport_threshold_degrees=45\nnozzle_temperature_c=210\nfirst_layer_nozzle_temperature_c=215\nbed_temperature_c=55\nfirst_layer_bed_temperature_c=55\nextrusion_multiplier=1\nmax_volumetric_speed_mm3_s=12\n")
        archive.writestr("Metadata/plate_1.gcode", gcode)
    return gcode


class AlloyLinuxTests(unittest.TestCase):
    def test_inspects_bundled_assembly_and_reports_parts(self) -> None:
        stats = inspect_model(ROOT / "app/src/main/assets/models/box-and-lid.stl")
        self.assertEqual(stats.format, "stl")
        self.assertEqual(stats.triangles, 24)
        self.assertEqual(stats.solids, 2)
        self.assertEqual(len(stats.parts), 2)
        self.assertEqual(stats.size_mm, (30.0, 24.0, 27.0))
        self.assertEqual(stats.boundary_edges, 0)

    def test_rejects_unsupported_model_extension(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model.ply"
            path.write_text("solid model")
            with self.assertRaises(AlloyError):
                inspect_model(path)

    def test_inspects_named_obj_parts_and_negative_indices(self) -> None:
        obj = (
            "o Box\n"
            "v 0 0 0\nv 10 0 0\nv 0 10 0\nv 0 0 10\n"
            "f 1 3 2\nf 1 2 4\nf 1 4 3\nf 2 3 4\n"
            "g Lid\n"
            "v 20 0 0\nv 30 0 0\nv 20 10 0\nv 20 0 10\n"
            "f -4 -2 -3\nf -4 -3 -1\nf -4 -1 -2\nf -2 -1 -3\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "parts.obj"
            path.write_text(obj)
            stats = inspect_model(path)
            self.assertEqual(stats.format, "obj")
            self.assertEqual(stats.triangles, 8)
            self.assertEqual([part.name for part in stats.parts], ["Box", "Lid"])

    def test_inspects_extensionless_obj_by_signature(self) -> None:
        obj = "v 0 0 0\nv 1 0 0\nv 0 1 0\nv 0 0 1\nf 1 3 2\nf 1 2 4\nf 1 4 3\nf 2 3 4\n"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "download"
            path.write_text(obj)
            stats = inspect_model(path)
            self.assertEqual(stats.format, "obj")
            self.assertEqual(stats.triangles, 4)

    def test_inspects_obj_even_when_provider_suffix_is_stl(self) -> None:
        obj = "v\t0 0 0\nv\t1 0 0\nv\t0 1 0\nv\t0 0 1\nf\t1 3 2\nf\t1 2 4\nf\t1 4 3\nf\t2 3 4\n"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "download.stl"
            path.write_text(obj)
            stats = inspect_model(path)
            self.assertEqual(stats.format, "obj")
            self.assertEqual(stats.triangles, 4)

    def test_inspects_bounded_multi_plate_alloy_project(self) -> None:
        obj = ("v 0 0 0\nv 1 0 0\nv 0 1 0\nv 0 0 1\n"
               "f 1 3 2\nf 1 2 4\nf 1 4 3\nf 2 3 4\n").encode()
        stl = ("solid tetra\n"
               "vertex 0 0 0\nvertex 1 0 0\nvertex 0 1 0\n"
               "vertex 0 0 0\nvertex 0 1 0\nvertex 0 0 1\n"
               "vertex 0 0 0\nvertex 0 0 1\nvertex 1 0 0\n"
               "vertex 1 0 0\nvertex 0 0 1\nvertex 0 1 0\n"
               "endsolid tetra\n").encode()
        files = {"models/0001.stl": stl, "models/0002.obj": obj}
        manifest = {
            "format": "alloy-project",
            "version": 2,
            "active_plate": 1,
            "recipe": {"printer": "A1 Mini", "filament": "PLA"},
            "plates": [
                {"index": 0, "name": "Box", "models": [{
                    "path": "models/0001.stl", "name": "box.stl", "bytes": len(stl), "sha256": hashlib.sha256(stl).hexdigest()
                }]},
                {"index": 1, "name": "Bow parts", "models": [{
                    "path": "models/0002.obj", "name": "bow-parts.obj", "bytes": len(obj), "sha256": hashlib.sha256(obj).hexdigest()
                }]},
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "workshop.alloy.zip"
            with ZipFile(path, "w") as archive:
                archive.writestr("alloy/project.json", json.dumps(manifest))
                for name, value in files.items():
                    archive.writestr(name, value)
            result = inspect_project(path)
            self.assertTrue(result["validated"])
            self.assertEqual(result["active_plate"], 1)
            self.assertEqual(result["model_count"], 2)
            self.assertEqual([plate["name"] for plate in result["plates"]], ["Box", "Bow parts"])
            self.assertEqual(result["plates"][1]["models"][0]["inspection"]["format"], "obj")
            self.assertEqual(result["plates"][0]["models"][0]["inspection"]["triangles"], 4)

    def test_rejects_unreferenced_v2_project_model(self) -> None:
        model = ("v 0 0 0\nv 1 0 0\nv 0 1 0\nv 0 0 1\n"
                 "f 1 3 2\nf 1 2 4\nf 1 4 3\nf 2 3 4\n").encode()
        manifest = {"format": "alloy-project", "version": 2, "active_plate": 0,
                    "plates": [{"index": 0, "name": "Plate 1", "models": []}]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "unreferenced.alloy.zip"
            with ZipFile(path, "w") as archive:
                archive.writestr("alloy/project.json", json.dumps(manifest))
                archive.writestr("models/0001.obj", model)
            with self.assertRaises(AlloyError):
                inspect_project(path)

    def test_rejects_oversized_ascii_stl_line(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "hostile.stl"
            path.write_text("solid hostile\n" + "vertex " + (" " * (1024 * 1024)) + "\n")
            with self.assertRaises(AlloyError):
                inspect_model(path)

    def test_inspects_3mf_build_item_transform_and_declared_unit(self) -> None:
        model = """<?xml version="1.0"?><model unit="centimeter"><resources><object id="1" name="Tetra"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/><vertex x="0" y="0" z="1"/></vertices><triangles><triangle v1="0" v2="2" v3="1"/><triangle v1="0" v2="1" v3="3"/><triangle v1="0" v2="3" v3="2"/><triangle v1="1" v2="2" v3="3"/></triangles></mesh></object></resources><build><item objectid="1" transform="1 0 0 0 1 0 0 0 1 2 3 4"/></build></model>"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tetra.3mf"
            with ZipFile(path, "w") as archive:
                archive.writestr("_rels/.rels", "<Relationships><Relationship Type=\"3dmodel\" Target=\"/3D/3dmodel.model\"/></Relationships>")
                archive.writestr("3D/3dmodel.model", model)
            stats = inspect_model(path)
            self.assertEqual(stats.format, "3mf")
            self.assertEqual(stats.triangles, 4)
            self.assertEqual(stats.solids, 1)
            self.assertEqual(stats.size_mm, (10.0, 10.0, 10.0))
            self.assertEqual(stats.parts[0].name, "Tetra")

    def test_inspects_3mf_component_instance_and_root_relationship(self) -> None:
        model = """<model unit="inch"><resources><object id="1" name="Box"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/><vertex x="0" y="0" z="1"/></vertices><triangles><triangle v1="0" v2="2" v3="1"/><triangle v1="0" v2="1" v3="3"/><triangle v1="0" v2="3" v3="2"/><triangle v1="1" v2="2" v3="3"/></triangles></mesh></object><object id="2" name="Lid"><components><component objectid="1" transform="1 0 0 0 1 0 0 0 1 2 0 0"/></components></object></resources><build><item objectid="1"/><item objectid="2"/></build></model>"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "assembly.3mf"
            with ZipFile(path, "w") as archive:
                archive.writestr("_rels/.rels", "<Relationships><Relationship Target=\"/3D/3dmodel.model\"/></Relationships>")
                archive.writestr("Metadata/thumbnail.png", b"\x89PNG\r\n\x1a\n")
                archive.writestr("3D/3dmodel.model", model)
            stats = inspect_model(path)
            self.assertEqual(stats.triangles, 8)
            self.assertEqual(stats.size_mm, (76.2, 25.4, 25.4))
            self.assertEqual([part.name for part in stats.parts], ["Box", "Lid"])

    def test_builds_non_shell_slicer_command_with_profiles(self) -> None:
        command = build_engine_command(
            Path("/opt/orca-slicer"),
            Path("/tmp/box.stl"),
            Path("/tmp/work"),
            Path("/tmp/work/box.gcode.3mf"),
            Path("/tmp/machine.json"),
            Path("/tmp/process.json"),
            Path("/tmp/filament.json"),
        )
        self.assertEqual(command[0], "/opt/orca-slicer")
        self.assertIn("--load-settings", command)
        self.assertIn("/tmp/machine.json;/tmp/process.json", command)
        self.assertIn("--load-filaments", command)
        self.assertNotIn("/bin/sh", command)

    def test_validates_the_minimum_alloy_gcode_package(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "box.gcode.3mf"
            with ZipFile(path, "w") as archive:
                archive.writestr("[Content_Types].xml", "3dmodel.model Metadata/plate_1.gcode application/vnd.ms-package.3dmanufacturing-3dmodel+xml")
                archive.writestr("_rels/.rels", "3dmodel /3D/3dmodel.model")
                archive.writestr("3D/_rels/3dmodel.model.rels", "<Relationships></Relationships>")
                archive.writestr("3D/3dmodel.model", "<model><resources><object><mesh><vertices><triangles></triangles></vertices></mesh></object></resources><build><item/></build></model>")
                archive.writestr("Metadata/plate_1.json", '{"engine_verified":false,"printer":"A1 mini","material":"PLA","layer_height_mm":0.2,"first_layer_height_mm":0.2,"infill_percent":15,"perimeters":2,"top_layers":4,"bottom_layers":4,"supports":false,"support_threshold_degrees":45,"nozzle_temperature_c":210,"first_layer_nozzle_temperature_c":215,"bed_temperature_c":55,"first_layer_bed_temperature_c":55,"extrusion_multiplier":1,"max_volumetric_speed_mm3_s":12}')
                archive.writestr("Metadata/slice_info.config", "[print]\nengine_verified=false\nlayer_height_mm=0.2\nfirst_layer_height_mm=0.2\ninfill_percent=15\nperimeters=2\ntop_layers=4\nbottom_layers=4\nsupports=false\nsupport_threshold_degrees=45\nnozzle_temperature_c=210\nfirst_layer_nozzle_temperature_c=215\nbed_temperature_c=55\nfirst_layer_bed_temperature_c=55\nextrusion_multiplier=1\nmax_volumetric_speed_mm3_s=12\n")
                archive.writestr("Metadata/plate_1.gcode", "G90\nM82\nM104 S0\n")
                archive.writestr("Metadata/plate_1.png", b"\x89PNG\r\n\x1a\n")
            result = validate_gcode_package(path)
            self.assertTrue(result["validated"])

    def test_validates_phone_batch_archive_and_nested_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = root / "first.gcode.3mf"
            second = root / "second.gcode.3mf"
            write_minimal_artifact(first)
            write_minimal_artifact(second)
            entries = {
                "plates/plate-01.gcode.3mf": first,
                "plates/plate-02.gcode.3mf": second,
            }
            manifest = {"format": "alloy-batch", "version": 1, "plates": []}
            for index, (entry, artifact) in enumerate(entries.items()):
                manifest["plates"].append({
                    "index": index,
                    "name": ("Box", "Lid")[index],
                    "entry": entry,
                    "bytes": artifact.stat().st_size,
                    "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
                    "layers": 1,
                    "filament_mm": 10,
                    "print_time_seconds": 1,
                    "engine": "test",
                    "engine_verified": False,
                })
            batch = root / "plates.alloy-batch.zip"
            with ZipFile(batch, "w") as archive:
                for entry, artifact in entries.items():
                    archive.write(artifact, entry)
                archive.writestr("alloy-batch.json", json.dumps(manifest))
            result = validate_batch_archive(batch)
            self.assertTrue(result["validated"])
            self.assertEqual([plate["name"] for plate in result["plates"]], ["Box", "Lid"])

            tampered = root / "tampered.alloy-batch.zip"
            with ZipFile(tampered, "w") as archive:
                archive.write(first, "plates/plate-01.gcode.3mf")
                archive.writestr("alloy-batch.json", json.dumps({
                    "format": "alloy-batch", "version": 1,
                    "plates": [{"index": 0, "name": "Box", "entry": "plates/plate-01.gcode.3mf",
                                 "bytes": first.stat().st_size, "sha256": "0" * 64}],
                }))
            with self.assertRaises(AlloyError):
                validate_batch_archive(tampered)

    def test_rejects_non_png_thumbnail(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.gcode.3mf"
            with ZipFile(path, "w") as archive:
                archive.writestr("[Content_Types].xml", "3dmodel.model Metadata/plate_1.gcode application/vnd.ms-package.3dmanufacturing-3dmodel+xml")
                archive.writestr("_rels/.rels", "3dmodel /3D/3dmodel.model")
                archive.writestr("3D/_rels/3dmodel.model.rels", "<Relationships></Relationships>")
                archive.writestr("3D/3dmodel.model", "<model><resources><object><mesh><vertices><triangles></triangles></vertices></mesh></object></resources><build><item/></build></model>")
                archive.writestr("Metadata/plate_1.json", '{"engine_verified":false,"printer":"A1 mini","material":"PLA","layer_height_mm":0.2,"first_layer_height_mm":0.2,"infill_percent":15,"perimeters":2,"top_layers":4,"bottom_layers":4,"supports":false,"support_threshold_degrees":45,"nozzle_temperature_c":210,"first_layer_nozzle_temperature_c":215,"bed_temperature_c":55,"first_layer_bed_temperature_c":55,"extrusion_multiplier":1,"max_volumetric_speed_mm3_s":12}')
                archive.writestr("Metadata/slice_info.config", "[print]\nengine_verified=false\nlayer_height_mm=0.2\nfirst_layer_height_mm=0.2\ninfill_percent=15\nperimeters=2\ntop_layers=4\nbottom_layers=4\nsupports=false\nsupport_threshold_degrees=45\nnozzle_temperature_c=210\nfirst_layer_nozzle_temperature_c=215\nbed_temperature_c=55\nfirst_layer_bed_temperature_c=55\nextrusion_multiplier=1\nmax_volumetric_speed_mm3_s=12\n")
                archive.writestr("Metadata/plate_1.gcode", "G90\nM82\nM104 S0\n")
                archive.writestr("Metadata/plate_1.png", b"not-a-png")
            with self.assertRaises(AlloyError):
                validate_gcode_package(path)

    def test_accepts_canonical_bambu_metadata_and_gcode_md5(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "canonical.gcode.3mf"
            gcode = b"G90\nM83\nM104 S0\n"
            with ZipFile(path, "w") as archive:
                archive.writestr("[Content_Types].xml", "<Types><Default Extension=\"gcode\" ContentType=\"text/x.gcode\"/><Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/></Types>")
                archive.writestr("_rels/.rels", "<Relationships><Relationship Type=\"3dmodel\" Target=\"/3D/3dmodel.model\"/></Relationships>")
                archive.writestr("3D/_rels/3dmodel.model.rels", "<Relationships></Relationships>")
                archive.writestr("3D/3dmodel.model", "<model><resources><object><mesh><vertices><triangles></triangles></vertices></mesh></object></resources><build><item/></build></model>")
                archive.writestr("Metadata/plate_1.json", '{"engine_verified":false,"printer":"A1 mini","material":"PLA","layer_height_mm":0.2,"first_layer_height_mm":0.2,"infill_percent":15,"perimeters":2,"top_layers":4,"bottom_layers":4,"supports":false,"support_threshold_degrees":45,"nozzle_temperature_c":210,"first_layer_nozzle_temperature_c":215,"bed_temperature_c":55,"first_layer_bed_temperature_c":55,"extrusion_multiplier":1,"max_volumetric_speed_mm3_s":12}')
                archive.writestr("Metadata/slice_info.config", '<config><header><header_item key="X-BBL-Client-Type" value="slicer"/></header><plate><metadata key="index" value="1"/><metadata key="gcode_file" value="Metadata/plate_1.gcode"/></plate></config>')
                archive.writestr("Metadata/model_settings.config", '<config><object id="1"><part id="1" subtype="model"/></object></config>')
                archive.writestr("Metadata/project_settings.config", '{"type":"project","printer_model":"A1 mini"}')
                archive.writestr("Metadata/print_profile.config", "; layer_height = 0.20\n; nozzle_diameter = 0.40\n; filament_diameter = 1.75\n")
                archive.writestr("Metadata/plate_1.gcode", gcode)
                archive.writestr("Metadata/plate_1.gcode.md5", hashlib.md5(gcode).hexdigest().upper())
            result = validate_gcode_package(path)
            self.assertTrue(result["validated"])

    def test_rejects_gcode_md5_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad-md5.gcode.3mf"
            gcode = b"G90\nM83\nM104 S0\n"
            with ZipFile(path, "w") as archive:
                archive.writestr("[Content_Types].xml", "3dmodel.model Metadata/plate_1.gcode application/vnd.ms-package.3dmanufacturing-3dmodel+xml")
                archive.writestr("_rels/.rels", "3dmodel /3D/3dmodel.model")
                archive.writestr("3D/_rels/3dmodel.model.rels", "<Relationships></Relationships>")
                archive.writestr("3D/3dmodel.model", "<model><resources><object><mesh><vertices><triangles></triangles></vertices></mesh></object></resources><build><item/></build></model>")
                archive.writestr("Metadata/plate_1.json", '{"engine_verified":false,"printer":"A1 mini","material":"PLA","layer_height_mm":0.2,"first_layer_height_mm":0.2,"infill_percent":15,"perimeters":2,"top_layers":4,"bottom_layers":4,"supports":false,"support_threshold_degrees":45,"nozzle_temperature_c":210,"first_layer_nozzle_temperature_c":215,"bed_temperature_c":55,"first_layer_bed_temperature_c":55,"extrusion_multiplier":1,"max_volumetric_speed_mm3_s":12}')
                archive.writestr("Metadata/slice_info.config", "[print]\nengine_verified=false\nlayer_height_mm=0.2\nfirst_layer_height_mm=0.2\ninfill_percent=15\nperimeters=2\ntop_layers=4\nbottom_layers=4\nsupports=false\nsupport_threshold_degrees=45\nnozzle_temperature_c=210\nfirst_layer_nozzle_temperature_c=215\nbed_temperature_c=55\nfirst_layer_bed_temperature_c=55\nextrusion_multiplier=1\nmax_volumetric_speed_mm3_s=12\n")
                archive.writestr("Metadata/plate_1.gcode", gcode)
                archive.writestr("Metadata/plate_1.gcode.md5", "0" * 32)
            with self.assertRaises(AlloyError):
                validate_gcode_package(path)

    def test_slice_publishes_only_after_fake_engine_package_passes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            executable = root / "fake-slicer"
            executable.write_text("""#!/usr/bin/env python3
from pathlib import Path
from zipfile import ZipFile
import sys

output = Path(sys.argv[sys.argv.index('--export-3mf') + 1])
with ZipFile(output, 'w') as archive:
    archive.writestr('[Content_Types].xml', '3dmodel.model Metadata/plate_1.gcode application/vnd.ms-package.3dmanufacturing-3dmodel+xml')
    archive.writestr('_rels/.rels', '3dmodel /3D/3dmodel.model')
    archive.writestr('3D/_rels/3dmodel.model.rels', '<Relationships></Relationships>')
    archive.writestr('3D/3dmodel.model', '<model><resources><object><mesh><vertices><triangles></triangles></vertices></mesh></object></resources><build><item/></build></model>')
    archive.writestr('Metadata/plate_1.json', '{\"engine_verified\":false,\"printer\":\"A1 mini\",\"material\":\"PLA\",\"layer_height_mm\":0.2,\"first_layer_height_mm\":0.2,\"infill_percent\":15,\"perimeters\":2,\"top_layers\":4,\"bottom_layers\":4,\"supports\":false,\"support_threshold_degrees\":45,\"nozzle_temperature_c\":210,\"first_layer_nozzle_temperature_c\":215,\"bed_temperature_c\":55,\"first_layer_bed_temperature_c\":55,\"extrusion_multiplier\":1,\"max_volumetric_speed_mm3_s\":12}')
    archive.writestr('Metadata/slice_info.config', '[print]\\nengine_verified=false\\nlayer_height_mm=0.2\\nfirst_layer_height_mm=0.2\\ninfill_percent=15\\nperimeters=2\\ntop_layers=4\\nbottom_layers=4\\nsupports=false\\nsupport_threshold_degrees=45\\nnozzle_temperature_c=210\\nfirst_layer_nozzle_temperature_c=215\\nbed_temperature_c=55\\nfirst_layer_bed_temperature_c=55\\nextrusion_multiplier=1\\nmax_volumetric_speed_mm3_s=12\\n')
    archive.writestr('Metadata/plate_1.gcode', 'G90\\nM83\\nM104 S0\\n')
""")
            executable.chmod(0o755)
            output = root / "out" / "box.gcode.3mf"
            args = type("SliceArgs", (), {
                "model": ROOT / "app/src/main/assets/models/box-20mm.stl",
                "engine": "orca",
                "executable": executable,
                "machine": None,
                "process": None,
                "filament": None,
                "output": output,
                "timeout": 30,
                "overwrite": False,
            })()
            result = slice_model(args)
            self.assertTrue(result["validated"])
            self.assertTrue(output.is_file())
            self.assertEqual(validate_gcode_package(output)["gcode_entry"], "Metadata/plate_1.gcode")

    def test_slice_does_not_publish_when_engine_returns_no_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            executable = root / "fake-slicer"
            executable.write_text("#!/usr/bin/env python3\n")
            executable.chmod(0o755)
            output = root / "out" / "missing.gcode.3mf"
            args = type("SliceArgs", (), {
                "model": ROOT / "app/src/main/assets/models/box-20mm.stl",
                "engine": "orca", "executable": executable,
                "machine": None, "process": None, "filament": None,
                "output": output, "timeout": 30, "overwrite": False,
            })()
            with self.assertRaisesRegex(AlloyError, "did not produce exactly one"):
                slice_model(args)
            self.assertFalse(output.exists())

    def test_slice_does_not_publish_an_invalid_engine_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            executable = root / "fake-slicer"
            executable.write_text("""#!/usr/bin/env python3
from pathlib import Path
import sys
output = Path(sys.argv[sys.argv.index('--export-3mf') + 1])
output.write_bytes(b'not a 3mf package')
""")
            executable.chmod(0o755)
            output = root / "out" / "invalid.gcode.3mf"
            args = type("SliceArgs", (), {
                "model": ROOT / "app/src/main/assets/models/box-20mm.stl",
                "engine": "orca", "executable": executable,
                "machine": None, "process": None, "filament": None,
                "output": output, "timeout": 30, "overwrite": False,
            })()
            with self.assertRaises((AlloyError, OSError, ValueError)):
                slice_model(args)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
