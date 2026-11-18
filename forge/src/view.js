import * as THREE from 'three';
import { toCreasedNormals } from 'three/examples/jsm/utils/BufferGeometryUtils.js';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { TransformControls } from 'three/examples/jsm/controls/TransformControls.js';

const THEMES = {
  dark: { bg: 0x0e1215, line: [0.62, 0.72, 0.78], glow: [0.56, 0.94, 0.77], edge: 0x05080a, vol: 0x6f8590, grey: '#5d666c' },
  light: { bg: 0xeef1f3, line: [0.32, 0.4, 0.46], glow: [0.2, 0.62, 0.48], edge: 0x1d2429, vol: 0x8a99a2, grey: '#b7bcc0' },
};

// Infinite-feeling grid: lines brighten and "bloom" around the model and dissolve to nothing with distance.
function makeGrid() {
  const mat = new THREE.ShaderMaterial({
    transparent: true, depthWrite: false, side: THREE.DoubleSide,
    uniforms: {
      uCenter: { value: new THREE.Vector2() }, uR: { value: 40 }, uStep: { value: 1 },
      uLine: { value: new THREE.Vector3(0.62, 0.72, 0.78) }, uGlow: { value: new THREE.Vector3(0.56, 0.94, 0.77) }, uAmt: { value: 1 },
    },
    vertexShader: 'varying vec2 vP; void main(){ vec4 w = modelMatrix * vec4(position,1.0); vP = w.xy; gl_Position = projectionMatrix * viewMatrix * w; }',
    fragmentShader: `
      varying vec2 vP; uniform vec2 uCenter; uniform float uR, uStep, uAmt; uniform vec3 uLine, uGlow;
      float grid(vec2 p, float s){ vec2 g = p / s; vec2 w = fwidth(g); vec2 a = abs(fract(g - 0.5) - 0.5) / w; float l = 1.0 - min(min(a.x, a.y), 1.0);
        float fade = 1.0 - smoothstep(0.25, 0.9, max(w.x, w.y)); return l * fade; }
      void main(){
        float d = length(vP - uCenter);
        float near = exp(-pow(d / (uR * 0.85), 2.0));            // bloom core
        float far = 1.0 - smoothstep(uR * 0.55, uR * 2.3, d);       // dissolve into nothing
        float minor = grid(vP, uStep), major = grid(vP, uStep * 10.0);
        float l = max(minor * 0.35, major) * far * far;
        vec3 col = mix(uLine, uGlow, near * 0.75);
        float a = (l * (0.16 + 0.6 * near) + near * near * 0.12) * uAmt;
        if (a < 0.003) discard;
        gl_FragColor = vec4(col, a);
      }`,
  });
  const m = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), mat);
  m.renderOrder = -1;
  return m;
}

export function createView(el, cb) {
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  renderer.localClippingEnabled = true;
  el.appendChild(renderer.domElement);
  const scene = new THREE.Scene();
  THREE.Object3D.DEFAULT_UP.set(0, 0, 1);
  const persp = new THREE.PerspectiveCamera(35, 1, 0.5, 8000);
  const ortho = new THREE.OrthographicCamera(-1, 1, 1, -1, -8000, 8000);
  let cam = persp;
  persp.up.set(0, 0, 1); ortho.up.set(0, 0, 1);
  persp.position.set(90, -120, 90);
  const controls = new OrbitControls(persp, renderer.domElement);
  controls.enableDamping = true; controls.dampingFactor = 0.12;

  let theme = THEMES.dark;
  scene.fog = new THREE.Fog(theme.bg, 200, 900);
  scene.add(new THREE.HemisphereLight(0xe6eef2, 0x30383d, 1.15));
  const key = new THREE.DirectionalLight(0xffffff, 1.25); key.position.set(60, -80, 140); scene.add(key);
  const fill = new THREE.DirectionalLight(0xb7cfdc, 0.45); fill.position.set(-90, 70, 30); scene.add(fill);

  const grid = makeGrid(); scene.add(grid);
  const volGroup = new THREE.Group(); scene.add(volGroup);
  const modelGroup = new THREE.Group(); scene.add(modelGroup);
  const markGroup = new THREE.Group(); scene.add(markGroup);
  const clip = new THREE.Plane(new THREE.Vector3(0, 0, -1), 0);
  let clipOn = false, edgesOn = true, bounds = new THREE.Box3(new THREE.Vector3(-30, -30, 0), new THREE.Vector3(30, 30, 20));
  let pickables = [], printVol = [180, 180, 180], last = null, meta = {};

  // ── gizmo
  const proxy = new THREE.Object3D(); scene.add(proxy);
  const gizmo = new TransformControls(persp, renderer.domElement);
  gizmo.setSize(0.85);
  const gHelper = gizmo.getHelper ? gizmo.getHelper() : gizmo;
  scene.add(gHelper);
  let gizmoCb = null;
  gizmo.addEventListener('dragging-changed', (e) => {
    controls.enabled = !e.value;
    if (!e.value && gizmoCb) gizmoCb.end(read());
  });
  gizmo.addEventListener('objectChange', () => { if (gizmoCb && gizmo.dragging) gizmoCb.change(read()); });
  const D = 180 / Math.PI;
  function read() {
    const e = new THREE.Euler().setFromQuaternion(proxy.quaternion, 'ZYX');
    return { pos: proxy.position.toArray(), rot: [e.x * D, e.y * D, e.z * D] };
  }
  function attachGizmo(pos, rot, mode, snap, cbs) {
    proxy.position.fromArray(pos);
    proxy.rotation.set(rot[0] / D, rot[1] / D, rot[2] / D, 'ZYX');
    gizmo.setMode(mode); gizmo.setSpace(mode === 'rotate' ? 'local' : 'world');
    gizmo.setTranslationSnap(snap ? snap.t : null); gizmo.setRotationSnap(snap ? snap.r / D : null);
    gizmoCb = cbs;
    if (gizmo.object !== proxy) gizmo.attach(proxy);
  }
  function detachGizmo() { gizmoCb = null; gizmo.detach(); controls.enabled = true; }

  function clear(g) { while (g.children.length) { const c = g.children.pop(); c.geometry?.dispose(); if (c.material) [].concat(c.material).forEach((m) => m.dispose()); } }

  function geom(vw) {
    const g0 = new THREE.BufferGeometry();
    g0.setAttribute('position', new THREE.BufferAttribute(vw.pos, 3));
    g0.setIndex(new THREE.BufferAttribute(vw.idx, 1));
    const g = toCreasedNormals(g0, (35 * Math.PI) / 180); g0.dispose(); return g;
  }
  const inside = (bb) => bb.min[0] >= -printVol[0] / 2 - 1e-3 && bb.max[0] <= printVol[0] / 2 + 1e-3 && bb.min[1] >= -printVol[1] / 2 - 1e-3 && bb.max[1] <= printVol[1] / 2 + 1e-3 && bb.min[2] >= -1e-3 && bb.max[2] <= printVol[2] + 1e-3;

  function drawVolume() {
    clear(volGroup);
    const [x, y, z] = printVol;
    const g = new THREE.EdgesGeometry(new THREE.BoxGeometry(x, y, z));
    const l = new THREE.LineSegments(g, new THREE.LineDashedMaterial({ color: theme.vol, dashSize: 3, gapSize: 3, transparent: true, opacity: 0.18, fog: true }));
    l.computeLineDistances(); l.position.set(0, 0, z / 2); volGroup.add(l);
  }

  function setResult(res, m = {}) {
    last = res; meta = m;
    if (m.printVolume) printVol = m.printVolume;
    clear(modelGroup); pickables = [];
    const box = new THREE.Box3();
    const clipArr = () => (clipOn ? [clip] : []);
    const hidden = m.hidden || new Set();
    for (const b of res.bodies) {
      if (hidden.has(b.name) || (m.solo && m.solo !== b.name)) continue;
      const g = geom(b.view);
      const fits = inside(b.bbox);
      const sel = m.selBody === b.name;
      const mat = new THREE.MeshStandardMaterial({ color: fits ? b.color : theme.grey, roughness: 0.82, metalness: 0, clippingPlanes: clipArr(), emissive: sel ? 0x0c2a20 : 0x000000 });
      const mesh = new THREE.Mesh(g, mat); mesh.userData = { body: b.name };
      const back = new THREE.Mesh(g, new THREE.MeshBasicMaterial({ color: 0xff9d57, side: THREE.BackSide, clippingPlanes: clipArr() }));
      const edges = new THREE.LineSegments(new THREE.EdgesGeometry(g, 32), new THREE.LineBasicMaterial({ color: sel ? 0x8ff0c4 : theme.edge, transparent: true, opacity: sel ? 0.95 : 0.4, clippingPlanes: clipArr() }));
      edges.userData.edge = true; edges.visible = edgesOn || sel;
      modelGroup.add(mesh, back, edges); pickables.push(mesh);
      box.union(new THREE.Box3(new THREE.Vector3(...b.bbox.min), new THREE.Vector3(...b.bbox.max)));
    }
    for (const r of res.refs) {
      if (hidden.has(r.name)) continue;
      const g = geom(r.view);
      modelGroup.add(new THREE.Mesh(g, new THREE.MeshBasicMaterial({ color: 0x6ff0d0, transparent: true, opacity: 0.2, depthWrite: false, clippingPlanes: clipArr() })));
      modelGroup.add(new THREE.LineSegments(new THREE.EdgesGeometry(g, 32), new THREE.LineBasicMaterial({ color: 0x6ff0d0, transparent: true, opacity: 0.65, clippingPlanes: clipArr() })));
    }
    if (!box.isEmpty()) bounds = box;
    // grid follows the model
    const c = bounds.getCenter(new THREE.Vector3()), s = bounds.getSize(new THREE.Vector3());
    const R = Math.max(Math.hypot(s.x, s.y) * 0.6, 20);
    const u = grid.material.uniforms;
    u.uCenter.value.set(c.x, c.y); u.uR.value = R;
    u.uStep.value = Math.pow(10, Math.floor(Math.log10(Math.max(R, 10) / 2.5)));
    grid.scale.set(R * 10, R * 10, 1); grid.position.set(c.x, c.y, Math.min(0, bounds.min.z) - 0.01);
    drawVolume();
  }

  function fogFor(dist) { const r = bounds.getSize(new THREE.Vector3()).length(); scene.fog.near = dist + r * 0.4; scene.fog.far = dist + r * 4 + 300; }

  function fit() {
    const c = bounds.getCenter(new THREE.Vector3()), s = bounds.getSize(new THREE.Vector3());
    const r = Math.max(s.length() / 2, 10);
    const dir = new THREE.Vector3().subVectors(cam.position, controls.target).normalize();
    if (dir.lengthSq() < 0.5) dir.set(0.5, -0.7, 0.55).normalize();
    controls.target.copy(c);
    if (cam === persp) {
      const vf = (persp.fov * Math.PI) / 180, hf = 2 * Math.atan(Math.tan(vf / 2) * persp.aspect);
      const dist = r / Math.sin(Math.min(vf, hf) / 2);
      cam.position.copy(c).addScaledVector(dir, dist); fogFor(dist);
    } else { cam.position.copy(c).addScaledVector(dir, 600); ortho.zoom = 1; ortho.userData = { r }; resize(); fogFor(600); }
    controls.update();
  }

  function setView(name) {
    const c = bounds.getCenter(new THREE.Vector3());
    const d = { iso: [0.5, -0.7, 0.55], top: [0, -0.0001, 1], front: [0, -1, 0], right: [1, 0, 0], back: [0, 1, 0], left: [-1, 0, 0] }[name] || [0.5, -0.7, 0.55];
    controls.target.copy(c); cam.position.copy(c).add(new THREE.Vector3(...d).normalize().multiplyScalar(300)); fit();
  }

  function setOrtho(on) {
    const next = on ? ortho : persp;
    if (next === cam) return;
    next.position.copy(cam.position); next.quaternion.copy(cam.quaternion);
    cam = next; controls.object = cam; gizmo.camera = cam; controls.update(); fit();
  }

  function setSection(axis, offset) {
    clipOn = axis != null;
    if (clipOn) { clip.normal.set(axis === 'x' ? -1 : 0, axis === 'y' ? -1 : 0, axis === 'z' ? -1 : 0); clip.constant = offset; }
    modelGroup.traverse((o) => { if (o.material) { o.material.clippingPlanes = clipOn ? [clip] : []; o.material.needsUpdate = true; } });
  }
  function setEdges(on) { edgesOn = on; if (last) setResult(last, meta); }
  function setTheme(name) {
    theme = THEMES[name] || THEMES.dark;
    renderer.setClearColor(theme.bg); scene.fog.color.setHex(theme.bg);
    const u = grid.material.uniforms; u.uLine.value.set(...theme.line); u.uGlow.value.set(...theme.glow); u.uAmt.value = name === 'light' ? 1.25 : 1;
    if (last) setResult(last, meta);
  }
  function getBounds() { return { min: bounds.min.toArray(), max: bounds.max.toArray() }; }

  // ── picking (ignored while the gizmo is being used)
  const ray = new THREE.Raycaster();
  let down = null;
  renderer.domElement.addEventListener('pointerdown', (e) => { down = gizmo.axis ? null : [e.clientX, e.clientY, performance.now()]; });
  renderer.domElement.addEventListener('pointerup', (e) => {
    if (!down || gizmo.dragging || Math.hypot(e.clientX - down[0], e.clientY - down[1]) > 5 || performance.now() - down[2] > 450) { down = null; return; }
    down = null;
    const r = renderer.domElement.getBoundingClientRect();
    const ndc = new THREE.Vector2(((e.clientX - r.left) / r.width) * 2 - 1, -((e.clientY - r.top) / r.height) * 2 + 1);
    ray.setFromCamera(ndc, cam);
    const hits = ray.intersectObjects(pickables, false).filter((h) => !clipOn || clip.distanceToPoint(h.point) <= 0);
    if (cb.onPick) cb.onPick(hits.length ? { point: hits[0].point.toArray(), normal: hits[0].face.normal.toArray(), body: hits[0].object.userData.body, shift: e.shiftKey } : null);
  });

  function setMarks(points) {
    clear(markGroup);
    const s = Math.max(bounds.getSize(new THREE.Vector3()).length() / 120, 0.4);
    for (const p of points) {
      const m = new THREE.Mesh(new THREE.SphereGeometry(s, 12, 8), new THREE.MeshBasicMaterial({ color: 0xffe066, depthTest: false }));
      m.position.fromArray(p); m.renderOrder = 10; markGroup.add(m);
    }
    if (points.length === 2) {
      const l = new THREE.Line(new THREE.BufferGeometry().setFromPoints(points.map((p) => new THREE.Vector3(...p))), new THREE.LineBasicMaterial({ color: 0xffe066, depthTest: false }));
      l.renderOrder = 10; markGroup.add(l);
    }
  }

  function resize() {
    const w = el.clientWidth || 1, h = el.clientHeight || 1;
    renderer.setSize(w, h, false); renderer.domElement.style.width = '100%'; renderer.domElement.style.height = '100%';
    persp.aspect = w / h; persp.updateProjectionMatrix();
    const rad = (ortho.userData?.r || 60) * 1.6;
    ortho.left = -rad * (w / h); ortho.right = rad * (w / h); ortho.top = rad; ortho.bottom = -rad; ortho.updateProjectionMatrix();
  }
  new ResizeObserver(resize).observe(el); resize();
  setTheme('dark');
  (function loop() { requestAnimationFrame(loop); controls.update(); renderer.render(scene, cam); })();

  return { setResult, fit, setView, setOrtho, setSection, setEdges, setMarks, setTheme, getBounds, attachGizmo, detachGizmo, isDragging: () => gizmo.dragging, snapshot: () => { renderer.render(scene, cam); return renderer.domElement.toDataURL('image/png'); } };
}
