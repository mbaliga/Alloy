// Sample extension. Answers calls from its pane and draws an overlay. All privileged work goes through forge.* and is permission-checked.
forge.ui.pane.onCall(async (paneId, method) => {
  if (method === 'scan') {
    const bodies = (await forge.geometry.list()).filter((b) => !b.ref);
    if (!bodies.length) return { bodies: 0, text: 'No bodies in the build.' };
    let n = 0; const pos = [], val = [];
    for (const b of bodies) {
      const m = await forge.geometry.mesh(b.name);
      const step = Math.max(1, Math.floor(m.positions.length / 3 / 4000)); // at most ~4000 points per body
      for (let i = 0; i < m.positions.length / 3; i += step) { pos.push(m.positions[i * 3], m.positions[i * 3 + 1], m.positions[i * 3 + 2]); val.push(m.positions[i * 3 + 2]); n++; }
    }
    await forge.scene.overlay('height', { kind: 'points', positions: Float32Array.from(pos), values: Float32Array.from(val), unit: 'mm', label: 'Vertex height', validity: 'estimate' });
    return { bodies: bodies.length, points: n, text: `${bodies.length} bodies, ${n} points. Tap the model to read a value.` };
  }
  if (method === 'clear') { await forge.scene.clearOverlay('height'); return { text: 'Overlay cleared.' }; }
  throw new Error('unknown method ' + method);
});
