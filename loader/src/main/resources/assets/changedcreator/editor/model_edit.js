/* 胶兽编辑器 - 方块/面 编辑（移动·旋转·缩放·对称·部件绑定·UV 打包） */
"use strict";

let _cubeSeq = 1;
let _fillingPane = false;
const MODEL_UNDO_LIMIT = 40;
let modelUndo = [];
let modelRedo = [];

function nextCubeId() { return "c_" + (_cubeSeq++); }
function nextGroupId() { return "g_" + (_cubeSeq++); }

function cubeCenter(c) {
  return [
    (c.min[0] + c.max[0]) / 2,
    (c.min[1] + c.max[1]) / 2,
    (c.min[2] + c.max[2]) / 2,
  ];
}
function cubeSize(c) {
  return [
    Math.abs(c.max[0] - c.min[0]),
    Math.abs(c.max[1] - c.min[1]),
    Math.abs(c.max[2] - c.min[2]),
  ];
}
function cubeRot(c) {
  const r = c.rot || [0, 0, 0];
  return [r[0] || 0, r[1] || 0, r[2] || 0];
}
function roundInt(n) { return Math.round(n); }

const partParent = new WeakMap();
function walkParts(node, fn, parent) {
  if (!node) return;
  if (parent) partParent.set(node, parent);
  else partParent.delete(node);
  fn(node, parent);
  (node.children || []).forEach((ch) => walkParts(ch, fn, node));
}

const DUP_PART_NAMES = new Set(["rootModelPart", "NULL_PART", "root"]);
function isDupPartName(name) {
  return !name || DUP_PART_NAMES.has(name) || /^f_\d+_$/.test(name);
}

function collectSubNames(node, acc) {
  if (!node) return acc;
  acc.add(node.name);
  (node.children || []).forEach((ch) => collectSubNames(ch, acc));
  return acc;
}

/** Does any OTHER root child contain a subtree named `name`? */
function duplicatedInOtherChild(name, siblings, selfIndex) {
  for (let i = 0; i < siblings.length; i++) {
    if (i === selfIndex) continue;
    const sub = collectSubNames(siblings[i], new Set());
    if (sub.has(name)) return true;
  }
  return false;
}

/** Drop stacked copies (rootModelPart / SRG fields / a root Tail that lives under Torso too). */
function dedupeModelTree(model) {
  if (!model || !model.root || !Array.isArray(model.root.children)) return;
  const kids = model.root.children;
  // 1) hard names
  let filtered = kids.filter((ch) => !isDupPartName(ch.name));
  // 2) any root child whose name also appears inside another sibling's subtree is a dup
  const kept = [];
  for (let i = 0; i < filtered.length; i++) {
    const ch = filtered[i];
    if (duplicatedInOtherChild(ch.name, filtered, i)) continue; // dup (e.g. Tail lives under Torso)
    kept.push(ch);
  }
  model.root.children = kept;
}

function ensureCubeIds(root) {
  if (!root) return;
  walkParts(root, (part) => {
    (part.cubes || []).forEach((c) => {
      if (!c.id) c.id = nextCubeId();
      if (!c.kind) {
        const s = cubeSize(c);
        c.kind = (s[0] < 1e-4 || s[1] < 1e-4 || s[2] < 1e-4) ? "plane" : "box";
        if (c.kind === "plane") {
          c.planeAxis = s[0] < 1e-4 ? "x" : s[1] < 1e-4 ? "y" : "z";
        }
      }
      if (!c.rot) c.rot = [0, 0, 0];
    });
  });
}

function findCube(id) {
  let found = null, partFound = null;
  if (!state.modelJson || !state.modelJson.root) return { cube: null, part: null };
  walkParts(state.modelJson.root, (part) => {
    (part.cubes || []).forEach((c) => {
      if (c.id === id) { found = c; partFound = part; }
    });
  });
  return { cube: found, part: partFound };
}

function allCubes() {
  const out = [];
  if (!state.modelJson || !state.modelJson.root) return out;
  walkParts(state.modelJson.root, (part) => {
    (part.cubes || []).forEach((c) => out.push({ cube: c, part }));
  });
  return out;
}

function partList() {
  const out = [];
  if (!state.modelJson || !state.modelJson.root) return out;
  walkParts(state.modelJson.root, (part) => out.push(part));
  return out;
}

function selectedCube() {
  const id = state.selectedCubeId;
  if (!id) return { cube: null, part: null };
  return findCube(id);
}

function rotatePoint(p, rot) {
  const [rx, ry, rz] = rot || [0, 0, 0];
  let x = p[0], y = p[1], z = p[2];
  if (rx) {
    const c = Math.cos(rx), s = Math.sin(rx);
    const ny = y * c - z * s, nz = y * s + z * c;
    y = ny; z = nz;
  }
  if (ry) {
    const c = Math.cos(ry), s = Math.sin(ry);
    const nx = x * c + z * s, nz = -x * s + z * c;
    x = nx; z = nz;
  }
  if (rz) {
    const c = Math.cos(rz), s = Math.sin(rz);
    const nx = x * c - y * s, ny = x * s + y * c;
    x = nx; y = ny;
  }
  return [x, y, z];
}

function rotatePointInv(p, rot) {
  const [rx, ry, rz] = rot || [0, 0, 0];
  return rotatePoint(p, [-rx, -ry, -rz].reverse() && [-rx, -ry, -rz]);
}

function localToRoot(part, p) {
  let v = p.slice();
  let node = part;
  while (node) {
    v = rotatePoint(v, node.rot || [0, 0, 0]);
    const pos = node.pos || [0, 0, 0];
    v[0] += pos[0]; v[1] += pos[1]; v[2] += pos[2];
    node = partParent.get(node) || null;
  }
  return v;
}

function rootToLocal(part, p) {
  const chain = [];
  let node = part;
  while (node) { chain.push(node); node = partParent.get(node) || null; }
  let v = p.slice();
  for (let i = chain.length - 1; i >= 0; i--) {
    const n = chain[i];
    const pos = n.pos || [0, 0, 0];
    v = [v[0] - pos[0], v[1] - pos[1], v[2] - pos[2]];
    const r = n.rot || [0, 0, 0];
    v = rotatePoint(v, [-r[0], -r[1], -r[2]]);
  }
  return v;
}

function originPointRoot(cubeOpt) {
  const cube = cubeOpt || (typeof selectedCube === "function" ? selectedCube().cube : null);
  const sel = $("symOrigin");
  const val = sel ? sel.value : "midline";
  if (cube && Array.isArray(cube.symOrigin)) return cube.symOrigin.slice();
  if (val && val !== "midline") {
    const { cube: c, part } = findCube(val);
    if (c && part) return localToRoot(part, cubeCenter(c));
  }
  return [0, 0, 0];
}

function currentAxes() {
  const a = [];
  if ($("symX") && $("symX").checked) a.push("x");
  if ($("symY") && $("symY").checked) a.push("y");
  if ($("symZ") && $("symZ").checked) a.push("z");
  return a;
}

function axisIndex(ax) { return ax === "x" ? 0 : ax === "y" ? 1 : 2; }

function uvFootprint(w, h, d, kind) {
  w = Math.max(kind === "plane" ? 1 : 0, roundInt(Math.abs(w)));
  h = Math.max(kind === "plane" ? 1 : 0, roundInt(Math.abs(h)));
  d = Math.max(0, roundInt(Math.abs(d)));
  if (kind === "plane" || d === 0) return { w: Math.max(1, w) * 2, h: Math.max(1, h) };
  // MC net: [d][w][d][w] wide × [d]+[h] tall — six faces (west/north/east/south + up/down)
  return { w: 2 * w + 2 * Math.max(1, d), h: h + Math.max(1, d) };
}

function faceUvRect(face) {
  if (!face.verts || !face.verts.length) return null;
  let u0 = 1, u1 = 0, v0 = 1, v1 = 0;
  face.verts.forEach((vt) => {
    u0 = Math.min(u0, vt.uv[0]); u1 = Math.max(u1, vt.uv[0]);
    v0 = Math.min(v0, vt.uv[1]); v1 = Math.max(v1, vt.uv[1]);
  });
  const x = Math.floor(u0 * texSize), y = Math.floor(v0 * texSize);
  const w = Math.max(1, Math.ceil(u1 * texSize) - x);
  const h = Math.max(1, Math.ceil(v1 * texSize) - y);
  return { x, y, w, h };
}

function cubeUvRects(cube, skipMirrors) {
  const rects = [];
  const src = (skipMirrors && cube.mirrorOf) ? findCube(cube.mirrorOf).cube : cube;
  const target = src || cube;
  const layout = target.uvLayout;
  if (layout) {
    if (layout.mode === "split" && layout.pieces) {
      layout.pieces.forEach((p) => rects.push({ x: p.x, y: p.y, w: p.w, h: p.h }));
    } else if (layout.w && layout.h) {
      rects.push({ x: layout.x, y: layout.y, w: layout.w, h: layout.h });
    }
  }
  (target.faces || []).forEach((f) => {
    const r = faceUvRect(f);
    if (r) rects.push(r);
  });
  return rects;
}

function occupiedPixelRects(excludeIds) {
  const skip = new Set(excludeIds || []);
  const rects = [];
  allCubes().forEach(({ cube }) => {
    if (skip.has(cube.id)) return;
    if (cube.mirrorOf && skip.has(cube.mirrorOf)) return;
    (cube.faces || []).forEach((face) => {
      const r = faceUvRect(face);
      if (r) rects.push(r);
    });
  });
  return rects;
}

function overlaps(a, b) {
  return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
}

function findUvSlotIn(pw, ph, used) {
  pw = Math.max(1, pw); ph = Math.max(1, ph);
  if (pw > texSize || ph > texSize) return null;
  for (let y = 0; y <= texSize - ph; y++) {
    for (let x = 0; x <= texSize - pw; x++) {
      const cand = { x, y, w: pw, h: ph };
      if (!used.some((r) => overlaps(cand, r))) return cand;
    }
  }
  return null;
}

/** Try normal, 90°-rotated, then split into strips. Returns {ok, pieces, rotated} or {ok:false}. */
function allocateUvLayout(w, h, d, kind, excludeIds) {
  const used = occupiedPixelRects(excludeIds);
  const pieces = uvPieces(w, h, d, kind);
  // 1) single net
  const fp = uvFootprint(w, h, d, kind);
  let slot = findUvSlotIn(fp.w, fp.h, used);
  if (slot) return { ok: true, mode: "net", rotated: false, x: slot.x, y: slot.y, w: slot.w, h: slot.h, pieces: null };
  slot = findUvSlotIn(fp.h, fp.w, used);
  if (slot) return { ok: true, mode: "net", rotated: true, x: slot.x, y: slot.y, w: slot.w, h: slot.h, pieces: null };
  // 2) split pieces (each may rotate)
  const placed = [];
  const used2 = used.slice();
  for (const p of pieces) {
    let s = findUvSlotIn(p.w, p.h, used2);
    let rot = false;
    if (!s) { s = findUvSlotIn(p.h, p.w, used2); rot = !!s; }
    if (!s) return { ok: false };
    const rect = rot ? { x: s.x, y: s.y, w: p.h, h: p.w, rotated: true, name: p.name } : { x: s.x, y: s.y, w: p.w, h: p.h, rotated: false, name: p.name };
    placed.push(rect);
    used2.push(rect);
  }
  return { ok: true, mode: "split", pieces: placed };
}

function uvPieces(w, h, d, kind) {
  w = Math.max(1, roundInt(Math.abs(w)));
  h = Math.max(1, roundInt(Math.abs(h)));
  d = Math.max(0, roundInt(Math.abs(d)));
  if (kind === "plane" || d === 0) {
    return [
      { name: "front", w: w, h: h },
      { name: "back", w: w, h: h },
    ];
  }
  const sideW = Math.max(1, d);
  // Each face packed on its own so thin parts (e.g. 1x1x8) don't waste a big rect.
  return [
    { name: "north", w: w, h: h },
    { name: "south", w: w, h: h },
    { name: "west", w: sideW, h: h },
    { name: "east", w: sideW, h: h },
    { name: "up", w: w, h: sideW },
    { name: "down", w: w, h: sideW },
  ];
}

function pxUv(x, y) { return [x / texSize, y / texSize]; }

function paintUvRect(x, y, w, h, rgba) {
  if (!textureCtx) return;
  textureCtx.fillStyle = "rgba(" + rgba.join(",") + ")";
  textureCtx.fillRect(x, y, w, h);
  textureNeedsUpdate();
}

function clearUvRects(rects) {
  if (!textureCtx || !rects) return;
  rects.forEach((r) => textureCtx.clearRect(r.x, r.y, r.w, r.h));
  if (emissiveCtx) rects.forEach((r) => emissiveCtx.clearRect(r.x, r.y, r.w, r.h));
  textureNeedsUpdate();
  if (emissiveTexture) emissiveTexture.needsUpdate = true;
}

function textureNeedsUpdate() {
  if (textureTexture) textureTexture.needsUpdate = true;
  try { state.texPngB64 = textureCanvas.toDataURL("image/png").split(",")[1]; } catch (e) { /* ignore */ }
  if (typeof syncTexDisplay === "function") syncTexDisplay();
}

function stampLayout(cube, layout) {
  cube.uvLayout = layout;
}

function faceUV(fu, fv, fw, fh, rotated, bx, by, bw, bh) {
  if (!rotated) {
    return [
      pxUv(fu, fv + fh), pxUv(fu + fw, fv + fh), pxUv(fu + fw, fv), pxUv(fu, fv),
    ];
  }
  // 90° CW inside the allocated slot (bx,by,bw,bh): (u,v) -> (bx + (v-by), by + bh - (u-bx)*fh/fw) — simpler: map local
  // local (0..fw, 0..fh) -> (fh-v, u) in rotated slot of size (fh, fw)
  const map = (u, v) => {
    const lu = u - fu, lv = v - fv;
    return pxUv(bx + lv, by + bw - lu);
  };
  // original unrotated corners BL BR TR TL in pixel space
  return [
    map(fu, fv + fh), map(fu + fw, fv + fh), map(fu + fw, fv), map(fu, fv),
  ];
}

function buildFacesForCube(min, max, layout, kind, planeAxis) {
  const x0 = min[0], y0 = min[1], z0 = min[2];
  const x1 = max[0], y1 = max[1], z1 = max[2];
  const w = Math.abs(x1 - x0), h = Math.abs(y1 - y0), d = Math.abs(z1 - z0);
  const faces = [];
  const quad = (pts, uvs, n) => {
    faces.push({
      verts: pts.map((p, i) => ({ p: p.slice(), uv: uvs[i].slice() })),
      normal: n.slice(),
    });
  };
  const rw = Math.max(1, roundInt(w));
  const rh = Math.max(1, roundInt(h));
  const rd = Math.max(0, roundInt(d));

  if (kind === "plane" || w < 1e-4 || h < 1e-4 || d < 1e-4) {
    const ax = planeAxis || (w < 1e-4 ? "x" : h < 1e-4 ? "y" : "z");
    const tw = ax === "x" ? Math.max(1, rd || roundInt(d) || rw) : rw;
    const th = ax === "y" ? Math.max(1, rd || rh) : rh;
    let uvA, uvB;
    if (layout && layout.mode === "split" && layout.pieces) {
      const a = layout.pieces[0], b = layout.pieces[1] || layout.pieces[0];
      uvA = [
        pxUv(a.x, a.y + a.h), pxUv(a.x + a.w, a.y + a.h), pxUv(a.x + a.w, a.y), pxUv(a.x, a.y),
      ];
      uvB = [
        pxUv(b.x, b.y + b.h), pxUv(b.x + b.w, b.y + b.h), pxUv(b.x + b.w, b.y), pxUv(b.x, b.y),
      ];
    } else {
      const u = layout.x, v = layout.y;
      const rotated = !!layout.rotated;
      if (!rotated) {
        uvA = [pxUv(u, v + th), pxUv(u + tw, v + th), pxUv(u + tw, v), pxUv(u, v)];
        uvB = [pxUv(u + tw, v + th), pxUv(u + tw * 2, v + th), pxUv(u + tw * 2, v), pxUv(u + tw, v)];
      } else {
        uvA = [pxUv(u, v + tw), pxUv(u + th, v + tw), pxUv(u + th, v), pxUv(u, v)];
        uvB = [pxUv(u, v + tw * 2), pxUv(u + th, v + tw * 2), pxUv(u + th, v + tw), pxUv(u, v + tw)];
      }
    }
    if (ax === "z") {
      quad([[x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0]], uvA, [0, 0, -1]);
      quad([[x1, y0, z0], [x0, y0, z0], [x0, y1, z0], [x1, y1, z0]], uvB, [0, 0, 1]);
    } else if (ax === "x") {
      quad([[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]], uvA, [-1, 0, 0]);
      quad([[x0, y0, z1], [x0, y0, z0], [x0, y1, z0], [x0, y1, z1]], uvB, [1, 0, 0]);
    } else {
      quad([[x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1]], uvA, [0, -1, 0]);
      quad([[x0, y0, z1], [x1, y0, z1], [x1, y0, z0], [x0, y0, z0]], uvB, [0, 1, 0]);
    }
    return faces;
  }

  const piece = (name) => (layout.pieces || []).find((p) => p.name === name);
  let uWest, uNorth, uEast, uSouth, vTop, vSide, rotated = !!layout.rotated;
  if (layout.mode === "split") {
    const uvS = (fu, fv, fw, fh) => [
      pxUv(fu, fv + fh), pxUv(fu + fw, fv + fh), pxUv(fu + fw, fv), pxUv(fu, fv),
    ];
    const n = piece("north"), s = piece("south"), wst = piece("west"), e = piece("east");
    const up = piece("up"), dn = piece("down");
    quad([[x1, y0, z0], [x0, y0, z0], [x0, y1, z0], [x1, y1, z0]], uvS(n.x, n.y, n.w, n.h), [0, 0, -1]);
    quad([[x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]], uvS(s.x, s.y, s.w, s.h), [0, 0, 1]);
    quad([[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]], uvS(wst.x, wst.y, wst.w, wst.h), [-1, 0, 0]);
    quad([[x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1]], uvS(e.x, e.y, e.w, e.h), [1, 0, 0]);
    quad([[x0, y1, z1], [x1, y1, z1], [x1, y1, z0], [x0, y1, z0]], uvS(up.x, up.y, up.w, up.h), [0, 1, 0]);
    quad([[x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1]], uvS(dn.x, dn.y, dn.w, dn.h), [0, -1, 0]);
    return faces;
  }

  const u = layout.x, v = layout.y;
  if (!rotated) {
    uWest = u; uNorth = u + rd; uEast = u + rd + rw; uSouth = u + rd + rw + rd;
    vTop = v; vSide = v + rd;
    const uv = (fu, fv, fw, fh) => [
      pxUv(fu, fv + fh), pxUv(fu + fw, fv + fh), pxUv(fu + fw, fv), pxUv(fu, fv),
    ];
    quad([[x1, y0, z0], [x0, y0, z0], [x0, y1, z0], [x1, y1, z0]], uv(uNorth, vSide, rw, rh), [0, 0, -1]);
    quad([[x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]], uv(uSouth, vSide, rw, rh), [0, 0, 1]);
    quad([[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]], uv(uWest, vSide, rd, rh), [-1, 0, 0]);
    quad([[x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1]], uv(uEast, vSide, rd, rh), [1, 0, 0]);
    quad([[x0, y1, z1], [x1, y1, z1], [x1, y1, z0], [x0, y1, z0]], uv(uNorth, vTop, rw, rd), [0, 1, 0]);
    quad([[x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1]], uv(uEast, vTop, rw, rd), [0, -1, 0]);
  } else {
    // rotated net: original (fp.w x fp.h) placed as (fp.h x fp.w)
    const uv = (lu, lv, fw, fh) => {
      // local in unrotated net -> rotated slot
      const mapc = (x, y) => pxUv(u + y, v + layout.w - x);
      return [mapc(lu, lv + fh), mapc(lu + fw, lv + fh), mapc(lu + fw, lv), mapc(lu, lv)];
    };
    const nWest = 0, nNorth = rd, nEast = rd + rw, nSouth = rd + rw + rd;
    const nTop = 0, nSide = rd;
    quad([[x1, y0, z0], [x0, y0, z0], [x0, y1, z0], [x1, y1, z0]], uv(nNorth, nSide, rw, rh), [0, 0, -1]);
    quad([[x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]], uv(nSouth, nSide, rw, rh), [0, 0, 1]);
    quad([[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]], uv(nWest, nSide, rd, rh), [-1, 0, 0]);
    quad([[x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1]], uv(nEast, nSide, rd, rh), [1, 0, 0]);
    quad([[x0, y1, z1], [x1, y1, z1], [x1, y1, z0], [x0, y1, z0]], uv(nNorth, nTop, rw, rd), [0, 1, 0]);
    quad([[x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1]], uv(nEast, nTop, rw, rd), [0, -1, 0]);
  }
  return faces;
}

function reallocUv(cube, excludeIds) {
  const s = cubeSize(cube);
  const layout = allocateUvLayout(s[0], s[1], s[2], cube.kind || "box", excludeIds);
  if (!layout.ok) return null;
  cube.faces = buildFacesForCube(cube.min, cube.max, layout, cube.kind || "box", cube.planeAxis);
  stampLayout(cube, layout);
  paintLayout(layout, [255, 80, 180, 90]);
  return layout;
}

function paintLayout(layout, rgba) {
  if (layout.mode === "split") {
    layout.pieces.forEach((p) => paintUvRect(p.x, p.y, p.w, p.h, rgba));
  } else {
    paintUvRect(layout.x, layout.y, layout.w, layout.h, rgba);
  }
}

function translateCube(cube, dx, dy, dz) {
  cube.min[0] += dx; cube.min[1] += dy; cube.min[2] += dz;
  cube.max[0] += dx; cube.max[1] += dy; cube.max[2] += dz;
  (cube.faces || []).forEach((f) => {
    (f.verts || []).forEach((vt) => {
      vt.p[0] += dx; vt.p[1] += dy; vt.p[2] += dz;
    });
  });
}

function setCubeSize(cube, sx, sy, sz, excludeIds) {
  const c = cubeCenter(cube);
  sx = Math.max(0, roundInt(sx));
  sy = Math.max(0, roundInt(sy));
  sz = Math.max(0, roundInt(sz));
  if (cube.kind === "plane") {
    const ax = cube.planeAxis || "z";
    if (ax === "x") sx = 0;
    if (ax === "y") sy = 0;
    if (ax === "z") sz = 0;
  }
  const oldMin = cube.min.slice(), oldMax = cube.max.slice();
  const oldRects = cubeUvRects(cube);
  cube.min = [c[0] - sx / 2, c[1] - sy / 2, c[2] - sz / 2];
  cube.max = [c[0] + sx / 2, c[1] + sy / 2, c[2] + sz / 2];
  const skip = (excludeIds || []).concat([cube.id]);
  const layout = ensureUvSpace(sx, sy, sz, cube.kind || "box", skip);
  if (!layout.ok) {
    cube.min = oldMin;
    cube.max = oldMax;
    return false;
  }
  clearUvRects(oldRects);
  cube.faces = buildFacesForCube(cube.min, cube.max, layout, cube.kind || "box", cube.planeAxis);
  stampLayout(cube, layout);
  paintLayout(layout, [255, 80, 180, 90]);
  return true;
}

function mirrorAcross(p, o) { return 2 * o - p; }

function mirrorCopy(src, axes, originRoot, part) {
  const c = JSON.parse(JSON.stringify(src));
  c.id = nextCubeId();
  c.mirrorOf = src.id;
  c.group = src.group;
  c.mirrorCombo = axes.slice();
  delete c.mirrorAxes;
  // Work in root space so origin is the real model origin, not the parent part origin.
  const minR = localToRoot(part, c.min);
  const maxR = localToRoot(part, c.max);
  axes.forEach((ax) => {
    const i = axisIndex(ax);
    const o = originRoot[i];
    const a = minR[i], b = maxR[i];
    minR[i] = Math.min(mirrorAcross(a, o), mirrorAcross(b, o));
    maxR[i] = Math.max(mirrorAcross(a, o), mirrorAcross(b, o));
    const r = cubeRot(c);
    if (ax === "x") { r[1] = -r[1]; r[2] = -r[2]; }
    if (ax === "y") { r[0] = -r[0]; r[2] = -r[2]; }
    if (ax === "z") { r[0] = -r[0]; r[1] = -r[1]; }
    c.rot = r;
  });
  c.min = rootToLocal(part, minR);
  c.max = rootToLocal(part, maxR);
  // Rebuild faces from src UVs (shared) with new corners
  const layout = src.uvLayout || { mode: "net", x: 0, y: 0, w: 1, h: 1, rotated: false };
  c.faces = buildFacesForCube(c.min, c.max, layout, c.kind || "box", c.planeAxis);
  axes.forEach((ax) => {
    const i = axisIndex(ax);
    (c.faces || []).forEach((f) => {
      if (f.normal) f.normal[i] = -f.normal[i];
      if (f.verts && f.verts.length === 4) f.verts = [f.verts[0], f.verts[3], f.verts[2], f.verts[1]];
    });
  });
  return c;
}

function axisCombos(axes) {
  const out = [];
  const n = 1 << axes.length;
  for (let m = 1; m < n; m++) {
    const a = [];
    axes.forEach((ax, i) => { if (m & (1 << i)) a.push(ax); });
    out.push(a);
  }
  return out;
}

function groupCubes(group) {
  return allCubes().filter(({ cube }) => cube.group === group);
}

function removeFromPart(part, cube) {
  part.cubes = (part.cubes || []).filter((c) => c !== cube && c.id !== cube.id);
}

/**
 * Make `src` the driving block of its symmetry group and re-mirror EVERY other
 * member so the whole group stays symmetric around `origin`. This means dragging
 * any member (source OR mirror) moves the entire group coherently.
 */
function syncMirrors(src, part, keepCombo) {
  if (!src.group) return;
  let axes = src.mirrorAxes || currentAxes();
  if (state.modelJson && state.modelJson.root) walkParts(state.modelJson.root, () => {});
  const origin = originPointRoot(src);
  src.symOrigin = origin.slice();
  const member = groupCubes(src.group);
  // If the driving block is itself a MIRROR, first transfer its pose back to the
  // real source (mirroring it again about origin recovers the source), so the
  // source is never deleted/replaced — the user's edit sticks to the mirrored one.
  let driving = src;
  if (src.mirrorOf && typeof findCube === "function") {
    const realSrc = findCube(src.mirrorOf).cube;
    if (realSrc) {
      // Prefer the source's stored axes so we don't drop a mirror axis.
      if (realSrc.mirrorAxes && realSrc.mirrorAxes.length) axes = realSrc.mirrorAxes.slice();
      // src is a mirror; mirror it back across ITS OWN combo to recover the source pose.
      const combo = src.mirrorCombo || [];
      if (combo.length) {
        const back = mirrorCopy(src, combo, origin, part);
        realSrc.min = back.min;
        realSrc.max = back.max;
        realSrc.rot = back.rot;
        realSrc.faces = back.faces;
      } else {
        realSrc.min = src.min.slice();
        realSrc.max = src.max.slice();
        realSrc.rot = cubeRot(src).slice();
      }
      delete realSrc.mirrorOf;
      realSrc.mirrorCombo = [];
      driving = realSrc;
    }
  }
  // Drop every member except the driving source.
  member.forEach(({ cube: c, part: p }) => {
    if (c.id !== driving.id) removeFromPart(p, c);
  });
  // Make the driving block the group's source.
  delete driving.mirrorOf;
  driving.mirrorCombo = [];
  driving.mirrorAxes = axes.slice();
  // Keep the user's selection on the block they were dragging: after rebuilding,
  // find the new mirror whose combo matches the dragged one (or the driving block).
  if (Array.isArray(keepCombo) && keepCombo.length) {
    let target = null;
    groupCubes(src.group).forEach(({ cube: c }) => {
      const mc = (c.mirrorCombo || []).slice().sort().join(",");
      const kc = keepCombo.slice().sort().join(",");
      if (mc === kc) target = c;
    });
    state.selectedCubeId = target ? target.id : driving.id;
  } else {
    state.selectedCubeId = driving.id;
  }
  if (!axes.length) return;
  axisCombos(axes).forEach((combo) => {
    const mk = mirrorCopy(driving, combo, origin, part);
    mk.id = nextCubeId();
    part.cubes.push(mk);
    if (Array.isArray(keepCombo) && keepCombo.length) {
      const mc = (mk.mirrorCombo || []).slice().sort().join(",");
      const kc = keepCombo.slice().sort().join(",");
      if (mc === kc) state.selectedCubeId = mk.id;
    }
  });
}

function defaultHostPart() {
  const { part } = selectedCube();
  if (part) return part;
  const parts = partList();
  const body = parts.find((p) => /body/i.test(p.name || ""));
  return body || parts[0] || (state.modelJson && state.modelJson.root);
}

function cloneModelJson(obj) {
  return JSON.parse(JSON.stringify(obj, (k, v) => (k === "_parent" ? undefined : v)));
}

function snapshotModel() {
  return {
    model: state.modelJson ? cloneModelJson(state.modelJson) : null,
    tex: textureCtx && textureCanvas ? textureCtx.getImageData(0, 0, textureCanvas.width, textureCanvas.height) : null,
    emi: emissiveCtx && emissiveCanvas ? emissiveCtx.getImageData(0, 0, emissiveCanvas.width, emissiveCanvas.height) : null,
    sel: state.selectedCubeId,
  };
}

function restoreModelSnap(snap) {
  state.modelJson = snap.model ? cloneModelJson(snap.model) : null;
  if (snap.tex && textureCtx) textureCtx.putImageData(snap.tex, 0, 0);
  if (snap.emi && emissiveCtx) emissiveCtx.putImageData(snap.emi, 0, 0);
  state.selectedCubeId = snap.sel;
  textureNeedsUpdate();
  if (emissiveTexture) emissiveTexture.needsUpdate = true;
  rebuildModel();
}

function pushModelUndo() {
  modelUndo.push(snapshotModel());
  if (modelUndo.length > MODEL_UNDO_LIMIT) modelUndo.shift();
  modelRedo = [];
}

function undoModelOrTex() {
  if (modelUndo.length) {
    modelRedo.push(snapshotModel());
    restoreModelSnap(modelUndo.pop());
    setStatus("已撤回");
    return;
  }
  if (typeof undo === "function") undo();
}

function redoModelOrTex() {
  if (modelRedo.length) {
    modelUndo.push(snapshotModel());
    restoreModelSnap(modelRedo.pop());
    setStatus("已重做");
    return;
  }
  if (typeof redo === "function") redo();
}

function expandTexture(newSize) {
  const old = texSize;
  if (!textureCanvas || newSize <= old) return false;
  const scale = old / newSize;
  allCubes().forEach(({ cube }) => {
    (cube.faces || []).forEach((f) => {
      (f.verts || []).forEach((vt) => {
        vt.uv[0] *= scale;
        vt.uv[1] *= scale;
      });
    });
  });
  const copy = (src) => {
    const tmp = document.createElement("canvas");
    tmp.width = old; tmp.height = old;
    tmp.getContext("2d").drawImage(src, 0, 0);
    return tmp;
  };
  const tKeep = copy(textureCanvas);
  const eKeep = emissiveCanvas ? copy(emissiveCanvas) : null;
  texSize = newSize;
  textureCanvas.width = newSize; textureCanvas.height = newSize;
  textureCtx = textureCanvas.getContext("2d");
  textureCtx.imageSmoothingEnabled = false;
  textureCtx.clearRect(0, 0, newSize, newSize);
  textureCtx.drawImage(tKeep, 0, 0);
  if (emissiveCanvas) {
    emissiveCanvas.width = newSize; emissiveCanvas.height = newSize;
    emissiveCtx = emissiveCanvas.getContext("2d");
    emissiveCtx.imageSmoothingEnabled = false;
    emissiveCtx.clearRect(0, 0, newSize, newSize);
    if (eKeep) emissiveCtx.drawImage(eKeep, 0, 0);
  }
  const vis = $("texCanvas"), ov = $("texOverlay");
  if (vis) { vis.width = newSize; vis.height = newSize; }
  if (ov) { ov.width = newSize; ov.height = newSize; overlayCtx = ov.getContext("2d"); overlayCtx.imageSmoothingEnabled = false; }
  if (typeof THREE !== "undefined") {
    if (textureTexture) textureTexture.dispose();
    textureTexture = new THREE.CanvasTexture(textureCanvas);
    textureTexture.flipY = false;
    textureTexture.magFilter = THREE.NearestFilter;
    if (emissiveTexture) emissiveTexture.dispose();
    if (emissiveCanvas) {
      emissiveTexture = new THREE.CanvasTexture(emissiveCanvas);
      emissiveTexture.flipY = false;
      emissiveTexture.magFilter = THREE.NearestFilter;
    }
  }
  if (typeof applyTexZoom === "function") applyTexZoom();
  textureNeedsUpdate();
  if (emissiveTexture) emissiveTexture.needsUpdate = true;
  setStatus("贴图已扩展为 " + newSize + "×" + newSize + "（原内容在左上角，新方块用右侧空位）");
  return true;
}

function ensureUvSpace(w, h, d, kind, excludeIds) {
  let layout = allocateUvLayout(w, h, d, kind, excludeIds);
  while (!layout.ok && texSize < 1024) {
    const next = texSize <= 96 ? 192 : texSize * 2;
    if (!expandTexture(next)) break;
    layout = allocateUvLayout(w, h, d, kind, excludeIds);
  }
  return layout;
}

function addCube(kind) {
  if (!state.modelJson || !state.modelJson.root) {
    setStatus("没有模型：请先加载形态或原版示例", false);
    return;
  }
  try {
  ensureCubeIds(state.modelJson.root);
  const part = defaultHostPart();
  if (!part) { setStatus("模型没有可挂接的部件", false); return; }
  if (!part.cubes) part.cubes = [];
  const sel = selectedCube().cube;
  const base = sel ? cubeCenter(sel) : [0, 4, 0];
  const isPlane = kind === "plane";
  const sx = 8, sy = 8, sz = isPlane ? 0 : 8;
  const layout = ensureUvSpace(sx, sy, sz, isPlane ? "plane" : "box", []);
  if (!layout.ok) {
    setStatus("贴图没有足够空位（已扩容仍放不下），拒绝新建", false);
    return;
  }
  pushModelUndo();
  const min = [base[0] - sx / 2, base[1] - sy / 2, base[2] + 4];
  const max = [min[0] + sx, min[1] + sy, min[2] + sz];
  const faces = buildFacesForCube(min, max, layout, isPlane ? "plane" : "box", "z");
  paintLayout(layout, [255, 80, 180, 90]);
  // Spawn origin = the host part's geometric center (part-local average of its
  // existing cubes), so blocks visibly grow OUT of their parent part.
  let animOrigin = [0, 0, 0];
  {
    let ox = 0, oy = 0, oz = 0, n = 0;
    (part.cubes || []).forEach((c) => {
      const cc = cubeCenter(c);
      ox += cc[0]; oy += cc[1]; oz += cc[2]; n++;
    });
    if (n > 0) animOrigin = [ox / n, oy / n, oz / n];
  }
  const cube = {
    id: nextCubeId(),
    kind: isPlane ? "plane" : "box",
    planeAxis: isPlane ? "z" : undefined,
    min, max,
    rot: [0, 0, 0],
    faces,
    uvLayout: layout,
    animOrigin,
    group: nextGroupId(),
    mirrorAxes: currentAxes().length ? currentAxes() : ["x"],
  };
  $("symX").checked = cube.mirrorAxes.indexOf("x") >= 0;
  $("symY").checked = cube.mirrorAxes.indexOf("y") >= 0;
  $("symZ").checked = cube.mirrorAxes.indexOf("z") >= 0;
  part.cubes.push(cube);
  syncMirrors(cube, part);
  state.selectedCubeId = cube.id;
  rebuildModel();
  setStatus("已新建" + (isPlane ? "面" : "方块") + "（整数尺寸，默认左右对称）");
  } catch (err) {
    setStatus("新建失败：" + (err && err.message ? err.message : err), false);
    console.error(err);
  }
}

function deleteSelected() {
  const { cube, part } = selectedCube();
  if (!cube || !part) { setStatus("先选中一个方块", false); return; }
  pushModelUndo();
  const src = cube.mirrorOf ? findCube(cube.mirrorOf).cube : cube;
  const rects = cubeUvRects(src || cube);
  const group = (src || cube).group;
  if (group) {
    groupCubes(group).forEach(({ cube: c, part: p }) => removeFromPart(p, c));
  } else {
    removeFromPart(part, cube);
  }
  clearUvRects(rects);
  state.selectedCubeId = null;
  state.selectedMesh = null;
  rebuildModel();
  setStatus("已删除方块并释放贴图占用" + (group ? "（含镜像）" : ""));
}

// Toggle: make the selected block the spawn origin ("主体块") for ALL editor-created
// blocks — at transfur they morph out of its position & size toward their targets,
// like Changed morphs vanilla cubes out of the body. Clicking again clears it.
function toggleAnimFrom() {
  if (!state.modelJson || !state.modelJson.root) { setStatus("没有模型", false); return; }
  const { cube } = selectedCube();
  if (!cube || !cube.id) { setStatus("先选中一个方块作为动画出生主体", false); return; }
  const custom = [];
  walkParts(state.modelJson.root, (part) => {
    (part.cubes || []).forEach((c) => { if (c.uvLayout) custom.push(c); });
  });
  if (!custom.length) { setStatus("没有新增块（只有原版提取的方块）", false); return; }
  const allSet = custom.every((c) => c.animFrom === cube.id);
  pushModelUndo();
  custom.forEach((c) => { if (allSet) delete c.animFrom; else c.animFrom = cube.id; });
  setStatus(allSet
    ? "已取消动画起点（新增块改回从父级关节长出），记得保存"
    : "动画起点 = 选中块 " + cube.id + "（新增块将从它的位置/大小变换到目标），记得保存");
}

function applySymmetry() {
  const { cube, part } = selectedCube();
  if (!cube || !part) { setStatus("先选中一个方块", false); return; }
  pushModelUndo();
  const src = cube.mirrorOf ? findCube(cube.mirrorOf).cube : cube;
  if (!src) return;
  if (!src.group) src.group = nextGroupId();
  src.mirrorAxes = currentAxes();
  const host = findCube(src.id).part || part;
  syncMirrors(src, host);
  state.selectedCubeId = src.id;
  rebuildModel();
  setStatus(src.mirrorAxes.length ? "已按模型原点复制镜像（共享贴图）" : "未勾选轴向，已去掉镜像");
}

function unlinkSymmetry() {
  const { cube } = selectedCube();
  if (!cube || !cube.group) { setStatus("当前方块没有对称组", false); return; }
  pushModelUndo();
  groupCubes(cube.group).forEach(({ cube: c }) => {
    delete c.group;
    delete c.mirrorOf;
    delete c.mirrorAxes;
  });
  rebuildModel();
  setStatus("已解除对称，镜像块变为独立方块");
}

function nudge(dx, dy, dz) {
  const { cube, part } = selectedCube();
  if (!cube) return;
  pushModelUndo();
  translateCube(cube, dx, dy, dz);
  const host = findCube(cube.id).part || part;
  syncMirrors(cube, host);
  state.selectedCubeId = cube.id;
  rebuildModel();
}

function setRot(rx, ry, rz) {
  const { cube, part } = selectedCube();
  if (!cube) return;
  pushModelUndo();
  cube.rot = [rx, ry, rz];
  const host = findCube(cube.id).part || part;
  syncMirrors(cube, host);
  state.selectedCubeId = cube.id;
  rebuildModel();
}

function setSizeFromInputs() {
  if (_fillingPane) return;
  const { cube, part } = selectedCube();
  if (!cube) return;
  const src = cube.mirrorOf ? findCube(cube.mirrorOf).cube : cube;
  const members = src.group ? groupCubes(src.group).map((x) => x.cube.id) : [src.id];
  pushModelUndo();
  const ok = setCubeSize(src, num("cubeSX"), num("cubeSY"), num("cubeSZ"), members);
  if (!ok) {
    modelUndo.pop();
    setStatus("贴图空位不足，尺寸未改（已尝试旋转/分割）", false);
    fillCubePane();
    return;
  }
  const host = findCube(src.id).part || part;
  syncMirrors(src, host);
  state.selectedCubeId = src.id;
  rebuildModel();
}

function applySize(sx, sy, sz) {
  const { cube, part } = selectedCube();
  if (!cube) return false;
  const src = cube.mirrorOf ? findCube(cube.mirrorOf).cube : cube;
  const members = src.group ? groupCubes(src.group).map((x) => x.cube.id) : [src.id];
  const ok = setCubeSize(src, sx, sy, sz, members);
  if (!ok) return false;
  const host = findCube(src.id).part || part;
  syncMirrors(src, host);
  state.selectedCubeId = src.id;
  rebuildModel();
  return true;
}

function num(id) {
  const v = parseFloat($(id).value);
  return Number.isFinite(v) ? v : 0;
}

function radToDeg(r) { return r * 180 / Math.PI; }
function degToRad(d) { return d * Math.PI / 180; }

function fillCubePane() {
  _fillingPane = true;
  const { cube, part } = selectedCube();
  const bind = $("cubeBind");
  const origin = $("symOrigin");
  if (bind) {
    const cur = part ? part.name : "";
    bind.innerHTML = "";
    partList().forEach((p) => {
      const opt = document.createElement("option");
      opt.value = p.name || "";
      opt.textContent = p.name || "(unnamed)";
      if (p === part) opt.selected = true;
      bind.appendChild(opt);
    });
    if (cur) bind.value = cur;
  }
  if (origin) {
    const keep = origin.value || "midline";
    origin.innerHTML = "";
    const mid = document.createElement("option");
    mid.value = "midline"; mid.textContent = "模型原点 (0,0,0)";
    origin.appendChild(mid);
    allCubes().forEach(({ cube: c, part: p }) => {
      if (c.mirrorOf) return;
      const opt = document.createElement("option");
      opt.value = c.id;
      opt.textContent = (p.name || "part") + " / " + c.id + (c.kind === "plane" ? " (面)" : "");
      origin.appendChild(opt);
    });
    origin.value = keep;
    if (![...origin.options].some((o) => o.value === keep)) origin.value = "midline";
  }
  const hint = $("cubeHint");
  if (!cube) {
    if (hint) hint.textContent = "点预览选中方块。G/R/S 或顶栏切换移动/旋转/缩放拖拽。";
    _fillingPane = false;
    if (typeof updateGizmos === "function") updateGizmos();
    return;
  }
  const src = cube;
  const c = cubeCenter(src);
  const s = cubeSize(src);
  const r = cubeRot(src);
  $("cubeX").value = round4(c[0]);
  $("cubeY").value = round4(c[1]);
  $("cubeZ").value = round4(c[2]);
  $("cubeRX").value = round4(radToDeg(r[0]));
  $("cubeRY").value = round4(radToDeg(r[1]));
  $("cubeRZ").value = round4(radToDeg(r[2]));
  $("cubeSX").value = roundInt(s[0]); $("cubeSXs").value = roundInt(s[0]);
  $("cubeSY").value = roundInt(s[1]); $("cubeSYs").value = roundInt(s[1]);
  $("cubeSZ").value = roundInt(s[2]); $("cubeSZs").value = roundInt(s[2]);
  const axes = src.mirrorAxes || [];
  $("symX").checked = axes.indexOf("x") >= 0;
  $("symY").checked = axes.indexOf("y") >= 0;
  $("symZ").checked = axes.indexOf("z") >= 0;
  if (hint) {
    hint.textContent = (src.kind === "plane" ? "面 " : "方块 ") + src.id
      + " · " + (part && part.name ? part.name : "?")
      + (src.group ? " · 对称 " + src.group : "")
      + (cube.mirrorOf ? "（镜像，编辑作用到源）" : "");
  }
  _fillingPane = false;
  if (typeof updateGizmos === "function") updateGizmos();
}

function round4(n) { return Math.round(n * 1000) / 1000; }

function rebindSelected() {
  const { cube, part } = selectedCube();
  if (!cube || !part) return;
  const name = $("cubeBind").value;
  const dest = partList().find((p) => p.name === name);
  if (!dest || dest === part) return;
  pushModelUndo();
  const src = cube.mirrorOf ? findCube(cube.mirrorOf).cube : cube;
  const members = src.group ? groupCubes(src.group).map((x) => x.cube) : [src];
  members.forEach((c) => {
    const loc = findCube(c.id);
    if (loc.part) removeFromPart(loc.part, c);
    if (!dest.cubes) dest.cubes = [];
    dest.cubes.push(c);
  });
  state.selectedCubeId = src.id;
  rebuildModel();
  setStatus("已绑定到部件 " + (dest.name || ""));
}

function applyCenterFromInputs() {
  if (_fillingPane) return;
  const { cube, part } = selectedCube();
  if (!cube) return;
  pushModelUndo();
  const cur = cubeCenter(cube);
  const nx = num("cubeX"), ny = num("cubeY"), nz = num("cubeZ");
  translateCube(cube, nx - cur[0], ny - cur[1], nz - cur[2]);
  const host = findCube(cube.id).part || part;
  syncMirrors(cube, host);
  state.selectedCubeId = cube.id;
  rebuildModel();
}

function applyRotFromInputs() {
  if (_fillingPane) return;
  setRot(degToRad(num("cubeRX")), degToRad(num("cubeRY")), degToRad(num("cubeRZ")));
}

function initCubeEditor() {
  if (!$("btnAddCube")) return;
  const pv = $("pivotMode");
  if (pv) {
    pv.addEventListener("change", () => {
      state.pivotMode = pv.value === "center" ? "center" : "last";
      if (typeof updateGizmos === "function") updateGizmos();
    });
  }
  $("btnAddCube").addEventListener("click", () => addCube("box"));
  $("btnAddPlane").addEventListener("click", () => addCube("plane"));
  $("btnDelCube").addEventListener("click", deleteSelected);
  $("btnApplySym").addEventListener("click", applySymmetry);
  $("btnUnlinkSym").addEventListener("click", unlinkSymmetry);
  $("btnAnimFrom").addEventListener("click", toggleAnimFrom);
  $("cubeBind").addEventListener("change", rebindSelected);
  ["cubeX", "cubeY", "cubeZ"].forEach((id) => $(id).addEventListener("change", applyCenterFromInputs));
  ["cubeRX", "cubeRY", "cubeRZ"].forEach((id) => $(id).addEventListener("change", applyRotFromInputs));
  ["cubeSX", "cubeSY", "cubeSZ"].forEach((id) => {
    $(id).addEventListener("change", setSizeFromInputs);
    const sl = $(id + "s");
    sl.addEventListener("input", () => { $(id).value = sl.value; });
    sl.addEventListener("change", () => { $(id).value = sl.value; setSizeFromInputs(); });
  });
  $("cubeMoveBtns").addEventListener("click", (e) => {
    const btn = e.target.closest("button[data-move]");
    if (!btn) return;
    const step = num("moveStep") || 1;
    const [x, y, z] = btn.getAttribute("data-move").split(",").map(Number);
    nudge(x * step, y * step, z * step);
  });
  $("cubeRotBtns").addEventListener("click", (e) => {
    const btn = e.target.closest("button[data-rot]");
    if (!btn) return;
    const step = degToRad(num("rotStep") || 15);
    const { cube } = selectedCube();
    if (!cube) return;
    const src = cube.mirrorOf ? findCube(cube.mirrorOf).cube : cube;
    const r = cubeRot(src);
    const [x, y, z] = btn.getAttribute("data-rot").split(",").map(Number);
    setRot(r[0] + x * step, r[1] + y * step, r[2] + z * step);
  });
  ["gizmoMove", "gizmoRotate", "gizmoScale"].forEach((id) => {
    const el = $(id);
    if (!el) return;
    el.addEventListener("click", () => toggleGizmoMode(id.replace("gizmo", "").toLowerCase()));
  });
}

function setGizmoMode(mode) {
  state.gizmoMode = mode;
  ["move", "rotate", "scale"].forEach((m) => {
    const el = $("gizmo" + m[0].toUpperCase() + m.slice(1));
    if (el) el.classList.toggle("active", state.gizmoMode === m);
  });
  if (typeof updateGizmos === "function") updateGizmos();
  setStatus(mode ? "拖拽模式：" + ({ move: "移动", rotate: "旋转", scale: "缩放" }[mode] || mode)
                 : "拖拽模式已关闭（点击方块选中，G/R/S 或顶栏开启）");
}

/** Pressing the same mode button/key again turns it off (all three inactive). */
function toggleGizmoMode(mode) {
  setGizmoMode(state.gizmoMode === mode ? null : mode);
}

/** Rebuild preview from current model JSON without reloading the texture canvas. */
function rebuildModel() {
  if (typeof renderModelFromState === "function") renderModelFromState();
  else if (typeof renderModel === "function") renderModel(state.modelJson, false);
}
