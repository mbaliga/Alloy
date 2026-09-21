#!/usr/bin/env python3
"""Small Linux windowed front end for Alloy's bounded model/artifact contract.

The GUI intentionally stays thin: it owns model viewing and workflow state,
while ``alloy_linux.py`` remains the single validation and external-slicer
bridge. Tkinter is the only optional Linux dependency (usually packaged as
``python3-tk``); the native slicer is still a separate, explicit gate.
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, simpledialog

try:
    from . import alloy_linux
except ImportError:  # Direct ``python3 tools/alloy_linux_gui.py`` invocation.
    import alloy_linux  # type: ignore


Triangle = alloy_linux.Triangle


class ModelCanvas(tk.Canvas):
    MAX_PREVIEW_TRIANGLES = 45_000

    def __init__(self, master: tk.Misc) -> None:
        super().__init__(master, background="#f8f7f4", highlightthickness=0)
        self.triangles: list[Triangle] = []
        self.model_center = (0.0, 0.0, 0.0)
        self.model_span = (1.0, 1.0, 1.0)
        self.yaw = math.radians(38.0)
        self.pitch = math.radians(24.0)
        self.zoom = 1.0
        self._last_pointer: tuple[int, int] | None = None
        self.bind("<Configure>", lambda _event: self.render())
        self.bind("<ButtonPress-1>", self._orbit_start)
        self.bind("<B1-Motion>", self._orbit_move)
        self.bind("<ButtonRelease-1>", lambda _event: setattr(self, "_last_pointer", None))
        self.bind("<MouseWheel>", self._wheel)
        self.bind("<Button-4>", lambda _event: self._zoom(1.1))
        self.bind("<Button-5>", lambda _event: self._zoom(1 / 1.1))

    def set_model(self, triangles: list[Triangle], span: tuple[float, float, float]) -> None:
        self.triangles = self._preview(triangles)
        self.model_span = span
        vertices = [vertex for triangle in triangles for vertex in triangle]
        self.model_center = tuple((min(vertex[index] for vertex in vertices) + max(vertex[index] for vertex in vertices)) / 2.0 for index in range(3))  # type: ignore[assignment]
        self.reset_view()

    def reset_view(self) -> None:
        self.yaw = math.radians(38.0)
        self.pitch = math.radians(24.0)
        self.zoom = 1.0
        self.render()

    def _preview(self, triangles: list[Triangle]) -> list[Triangle]:
        if len(triangles) <= self.MAX_PREVIEW_TRIANGLES:
            return triangles
        stride = math.ceil(len(triangles) / self.MAX_PREVIEW_TRIANGLES)
        return triangles[::stride]

    def _orbit_start(self, event: tk.Event) -> None:
        self._last_pointer = (event.x, event.y)

    def _orbit_move(self, event: tk.Event) -> None:
        if self._last_pointer is None:
            self._last_pointer = (event.x, event.y)
            return
        last_x, last_y = self._last_pointer
        self.yaw += (event.x - last_x) * 0.012
        self.pitch = max(-1.35, min(1.35, self.pitch + (event.y - last_y) * 0.012))
        self._last_pointer = (event.x, event.y)
        self.render()

    def _wheel(self, event: tk.Event) -> None:
        self._zoom(1.1 if event.delta > 0 else 1 / 1.1)

    def _zoom(self, amount: float) -> None:
        self.zoom = max(0.15, min(8.0, self.zoom * amount))
        self.render()

    def _project(self, vertex: tuple[float, float, float], scale: float, cx: float, cy: float) -> tuple[float, float, float]:
        x, y, z = (vertex[index] - self.model_center[index] for index in range(3))
        cos_yaw, sin_yaw = math.cos(self.yaw), math.sin(self.yaw)
        x1, y1 = x * cos_yaw - y * sin_yaw, x * sin_yaw + y * cos_yaw
        cos_pitch, sin_pitch = math.cos(self.pitch), math.sin(self.pitch)
        y2, depth = y1 * cos_pitch - z * sin_pitch, y1 * sin_pitch + z * cos_pitch
        return cx + x1 * scale, cy - y2 * scale, depth

    def render(self) -> None:
        self.delete("all")
        width, height = max(1, self.winfo_width()), max(1, self.winfo_height())
        if not self.triangles:
            self.create_text(width / 2, height / 2, text="Open an STL, OBJ or 3MF model", fill="#77736c", font=("TkDefaultFont", 14))
            return
        span = max(max(self.model_span), 1.0)
        scale = min(width, height) * 0.68 / span * self.zoom
        cx, cy = width / 2, height / 2 + min(height * 0.08, 60)

        # A quiet isometric build plate keeps the 3D silhouette legible without
        # turning the Linux surface into a second renderer implementation.
        plate = [(-90.0, -90.0, 0.0), (90.0, -90.0, 0.0), (90.0, 90.0, 0.0), (-90.0, 90.0, 0.0)]
        plate_points = [self._project(point, scale, cx, cy) for point in plate]
        self.create_polygon([(point[0], point[1]) for point in plate_points], fill="#eeece7", outline="#d3cfc7")
        for grid in range(-90, 91, 30):
            start = self._project((float(grid), -90.0, 0.0), scale, cx, cy)
            end = self._project((float(grid), 90.0, 0.0), scale, cx, cy)
            self.create_line(start[0], start[1], end[0], end[1], fill="#dfdcd5")
            start = self._project((-90.0, float(grid), 0.0), scale, cx, cy)
            end = self._project((90.0, float(grid), 0.0), scale, cx, cy)
            self.create_line(start[0], start[1], end[0], end[1], fill="#dfdcd5")

        projected = []
        for triangle in self.triangles:
            points = [self._project(vertex, scale, cx, cy) for vertex in triangle]
            projected.append((sum(point[2] for point in points) / 3.0, points))
        projected.sort(key=lambda item: item[0])
        for depth, points in projected:
            light = max(0, min(30, int(depth * 0.08)))
            fill = f"#{154 + light:02x}{132 + light:02x}{91 + light:02x}"
            self.create_polygon([(point[0], point[1]) for point in points], fill=fill, outline="#9a825f")
        self.create_text(16, 18, anchor="w", text="Drag to orbit  ·  scroll to zoom", fill="#77736c", font=("TkDefaultFont", 10))


class AlloyLinuxApp:
    EXAMPLES = (
        ("Box · 20 mm calibration cube", "box-20mm.stl"),
        ("Box + lid assembly", "box-and-lid.stl"),
        ("Mounting block", "mounting-block.stl"),
    )

    def __init__(self, root: tk.Tk, initial_model: Path | None) -> None:
        self.root = root
        self.root.title("Alloy · Phone-first workshop companion")
        self.root.geometry("1180x760")
        self.root.minsize(820, 560)
        self.root.configure(background="#f2f0eb")
        self.current_model: Path | None = None
        self.current_triangles: list[Triangle] = []
        self.stats: alloy_linux.ModelStats | None = None
        self.status = tk.StringVar(value="Open a model to begin")
        self.details = tk.StringVar(value="STL, OBJ and 3MF · bounded inspection · external slicer bridge")
        self._build_ui()
        if initial_model is not None:
            self.load_model(initial_model)
        else:
            self.load_model(self._example_path("box-and-lid.stl"), show_errors=False)

    def _build_ui(self) -> None:
        toolbar = tk.Frame(self.root, background="#f2f0eb", padx=18, pady=14)
        toolbar.pack(fill="x")
        tk.Label(toolbar, text="ALLOY", background="#f2f0eb", foreground="#211f1c", font=("Helvetica", 22, "bold")).pack(side="left", padx=(2, 24))
        for label, command in (("Open model", self.open_model), ("Examples", self.show_examples), ("Slice", self.slice_model), ("Validate artifact", self.validate_artifact)):
            tk.Button(toolbar, text=label, command=command, relief="flat", background="#ffffff", foreground="#3f3a34", padx=14, pady=7).pack(side="left", padx=4)
        tk.Button(toolbar, text="Reset view", command=lambda: self.canvas.reset_view(), relief="flat", background="#ffffff", foreground="#3f3a34", padx=14, pady=7).pack(side="right", padx=4)

        body = tk.Frame(self.root, background="#f2f0eb", padx=18, pady=2)
        body.pack(fill="both", expand=True)
        self.canvas = ModelCanvas(body)
        self.canvas.pack(side="left", fill="both", expand=True)
        side = tk.Frame(body, background="#ffffff", width=300, padx=18, pady=18)
        side.pack(side="right", fill="y", padx=(14, 0))
        side.pack_propagate(False)
        tk.Label(side, text="WORKSPACE", background="#ffffff", foreground="#8b7556", font=("Helvetica", 9, "bold")).pack(anchor="w")
        tk.Label(side, textvariable=self.status, background="#ffffff", foreground="#25221e", justify="left", wraplength=260, font=("Helvetica", 14, "bold")).pack(anchor="w", pady=(8, 16))
        tk.Label(side, textvariable=self.details, background="#ffffff", foreground="#77736c", justify="left", wraplength=260, font=("Helvetica", 10)).pack(anchor="w")
        tk.Frame(side, height=1, background="#e2ded5").pack(fill="x", pady=18)
        self.report = tk.Text(side, height=20, width=30, background="#ffffff", foreground="#59544d", relief="flat", wrap="word", font=("Courier", 9))
        self.report.pack(fill="both", expand=True)
        self.report.configure(state="disabled")
        tk.Label(self.root, text="", textvariable=self.status, anchor="w", background="#e2ded5", foreground="#5c574f", padx=18, pady=6).pack(fill="x")

    def _example_path(self, filename: str) -> Path:
        return Path(__file__).resolve().parents[1] / "app/src/main/assets/models" / filename

    def open_model(self) -> None:
        chosen = filedialog.askopenfilename(title="Open STL, OBJ or 3MF", filetypes=(("3D models", "*.stl *.obj *.3mf"), ("STL", "*.stl"), ("OBJ", "*.obj"), ("3MF", "*.3mf")))
        if chosen:
            self.load_model(Path(chosen))

    def show_examples(self) -> None:
        menu = tk.Toplevel(self.root)
        menu.title("Alloy model library")
        menu.configure(background="#ffffff")
        tk.Label(menu, text="Audited local examples", background="#ffffff", foreground="#25221e", font=("Helvetica", 14, "bold")).pack(anchor="w", padx=18, pady=(18, 4))
        tk.Label(menu, text="Choose one to open it in the 3D workspace.", background="#ffffff", foreground="#77736c").pack(anchor="w", padx=18, pady=(0, 12))
        for label, filename in self.EXAMPLES:
            tk.Button(menu, text=label, anchor="w", command=lambda name=filename: (menu.destroy(), self.load_model(self._example_path(name))), relief="flat", background="#f5f3ef", padx=12, pady=8).pack(fill="x", padx=18, pady=3)

    def load_model(self, path: Path, show_errors: bool = True) -> None:
        try:
            stats = alloy_linux.inspect_model(path.resolve())
            data = alloy_linux._read_bounded(path.resolve())
            if stats.format == "stl":
                triangles = alloy_linux._read_stl(path.resolve(), data)
            elif stats.format == "obj":
                triangles, _parts = alloy_linux._read_obj(path.resolve(), data)
            else:
                triangles, _parts = alloy_linux._read_3mf(path.resolve(), data)
            self.current_model, self.current_triangles, self.stats = path.resolve(), triangles, stats
            self.canvas.set_model(triangles, stats.size_mm)
            self.status.set(f"{path.name}\n{stats.format.upper()} · {stats.solids} solid(s)")
            self.details.set(f"{stats.size_mm[0]:.1f} × {stats.size_mm[1]:.1f} × {stats.size_mm[2]:.1f} mm\n{stats.triangles:,} triangles · {stats.boundary_edges} open edges")
            self._set_report(stats)
        except Exception as error:
            if show_errors:
                messagebox.showerror("Model rejected", str(error), parent=self.root)

    def _set_report(self, stats: alloy_linux.ModelStats) -> None:
        lines = ["GEOMETRY HEALTH", f"boundary edges  {stats.boundary_edges}", f"non-manifold    {stats.non_manifold_edges}", f"degenerate      {stats.degenerate_facets}", "", "PARTS"]
        lines.extend(f"{part.name}\n  {part.triangles:,} triangles · {part.size_mm[0]:.1f} × {part.size_mm[1]:.1f} × {part.size_mm[2]:.1f} mm" for part in stats.parts)
        self.report.configure(state="normal")
        self.report.delete("1.0", "end")
        self.report.insert("1.0", "\n".join(lines))
        self.report.configure(state="disabled")

    def slice_model(self) -> None:
        if self.current_model is None:
            messagebox.showinfo("Slice", "Open a model first", parent=self.root)
            return
        engine = simpledialog.askstring("Choose slicer", "Enter orca or bambu:", initialvalue="orca", parent=self.root)
        if engine not in alloy_linux.ENGINE_NAMES:
            if engine:
                messagebox.showerror("Slice", "Choose exactly orca or bambu", parent=self.root)
            return
        executable = filedialog.askopenfilename(title="Select slicer executable (Cancel uses PATH)", parent=self.root)
        destination = filedialog.asksaveasfilename(title="Save validated G-code package", defaultextension=".gcode.3mf", filetypes=(("G-code 3MF", "*.gcode.3mf"),), parent=self.root)
        if not destination:
            return
        output = Path(destination)
        overwrite = output.exists()
        if overwrite and not messagebox.askyesno("Overwrite artifact?", f"Replace {output.name}?", parent=self.root):
            return
        self.status.set(f"Slicing with {engine}…")
        self._run_slice(engine, Path(executable) if executable else None, output, overwrite)

    def _run_slice(self, engine: str, executable: Path | None, output: Path, overwrite: bool) -> None:
        def worker() -> None:
            try:
                args = argparse.Namespace(model=self.current_model, engine=engine, executable=executable, machine=None, process=None, filament=None, output=output, timeout=3600, overwrite=overwrite)
                result = alloy_linux.slice_model(args)
                self.root.after(0, lambda: self.status.set(f"Validated artifact · {result['bytes']:,} bytes"))
            except Exception as error:
                self.root.after(0, lambda: messagebox.showerror("Slice failed", str(error), parent=self.root))
                self.root.after(0, lambda: self.status.set("Slice failed · no artifact published"))
        threading.Thread(target=worker, name="alloy-linux-slice", daemon=True).start()

    def validate_artifact(self) -> None:
        chosen = filedialog.askopenfilename(title="Validate G-code 3MF", filetypes=(("G-code 3MF", "*.gcode.3mf"),), parent=self.root)
        if not chosen:
            return
        try:
            result = alloy_linux.validate_gcode_package(Path(chosen).resolve())
            self.status.set(f"Artifact valid · {result['bytes']:,} bytes")
        except Exception as error:
            messagebox.showerror("Artifact rejected", str(error), parent=self.root)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="alloy-linux-gui", description="Open and inspect STL/OBJ/3MF models on Linux")
    parser.add_argument("--model", type=Path, help="model to open on startup")
    args = parser.parse_args(argv)
    root = tk.Tk()
    AlloyLinuxApp(root, args.model)
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
