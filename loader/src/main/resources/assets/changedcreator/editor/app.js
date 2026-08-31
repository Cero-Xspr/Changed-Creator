/* 胶兽编辑器 - Changed Creator - 前端逻辑 */
"use strict";

/* ---------------------------------------------------------------- state */
let state = {
  forms: [],
  currentId: null,
  texPngB64: null,        // uploaded texture PNG (base64)
  modelJson: null,        // model tree for the current form
  exportBase: null,       // imported/exported payload for round-trip
  tool: "brush",          // brush | fill | pick
  layer: "base",          // base | emissive (glow layer)
  editMode: false,        // model-edit mode (E key): selected cube opaque, others translucent
  selectedMesh: null,     // currently selected cube mesh (model edit)
  selectedCubeId: null,   // JSON cube id (survives rebuild)
  hoverMesh: null,        // hovered cube mesh (highlight)
  camDefault: null,       // initial camera pos/target for U reset
  abilityIds: [],         // all registered ability ids (for validation/hints)
  gizmoMode: "move",      // move | rotate | scale | null (none)
  modelOffsetFrozen: null, // first-load preview offset so adding cubes doesn't recentre
  selection: [],          // multi-selected cube ids (Ctrl/Shift add)
  pivotMode: "last",      // rotate/scale pivot: last | center
};

const $ = (id) => document.getElementById(id);

/* ---------------------------------------------------------------- api */
async function api(path, opts) {
  const res = await fetch(path, opts);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}
function setStatus(msg, ok = true) {
  $("statusText").textContent = msg;
  $("statusText").style.color = ok ? "#9ecb5a" : "#e8705a";
}

/* ---------------------------------------------------------------- init */
async function init() {
  bindUi();
  initThree();
  initTexEditor();
  initModelInteraction();
  initKeyboard();
  if (typeof initCubeEditor === "function") initCubeEditor();
  initSplitters();
  await Promise.all([loadForms(), loadExamples(), loadAbilities()]);
  if (state.forms.length > 0) selectForm(state.forms[0].id);
  else setStatus("点击「＋ 新建形态」开始，或选择原版示例参考。");
}

function bindUi() {
  $("btnNew").addEventListener("click", newForm);
  $("btnExport").addEventListener("click", exportFile);
  $("btnImport").addEventListener("click", () => $("fileInput").click());
  $("fileInput").addEventListener("change", (e) => readLocalFile(e.target.files[0], fillFormFromImport));
  $("exampleSelect").addEventListener("change", (e) => {
    if (e.target.value) selectForm(e.target.value);
  });
  $("btnTexUpload").addEventListener("click", () => $("texInput").click());
  $("texInput").addEventListener("change", (e) => readPng(e.target.files[0]));
  $("btnUndo").addEventListener("click", undo);
  $("btnRedo").addEventListener("click", redo);
  $("btnTexExport").addEventListener("click", exportTexture);
  $("btnHotReg").addEventListener("click", hotRegister);
  $("btnEmissive").addEventListener("click", toggleEmissive);
  $("f_tint").addEventListener("input", () => renderModel(state.modelJson));
  $("f_texture").addEventListener("input", () => {
    // If the path matches the editor's loaded texture, re-render live; else
    // try loading the named texture so the preview reflects appearance changes now.
    if (state.modelJson) renderModel(state.modelJson, false, { reuseTexture: false });
  });
  $("editForm").addEventListener("submit", (e) => { e.preventDefault(); save(); });

  // ---- texture editor tools ----
  $("toolBrush").addEventListener("click", () => setTool("brush"));
  $("toolFill").addEventListener("click", () => setTool("fill"));
  $("toolPick").addEventListener("click", () => setTool("pick"));
  ["cR", "cG", "cB", "cA"].forEach((id) => $(id).addEventListener("input", syncColorFromSliders));
  $("cHex").addEventListener("change", syncColorFromHex);
  initColorWheel();
}

/* ---------------------------------------------------------------- forms */
async function loadForms() {
  state.forms = await api("/api/forms");
  const ul = $("formList");
  ul.innerHTML = "";
  state.forms.forEach((f) => {
    const li = document.createElement("li");
    const span = document.createElement("span");
    span.textContent = f.id;
    span.style.cursor = "pointer";
    span.addEventListener("click", () => selectForm(f.id));
    const del = document.createElement("button");
    del.textContent = "✕";
    del.title = "删除 " + f.id;
    del.style.cssText = "float:right;background:none;border:none;color:#e8705a;cursor:pointer;font-size:11px;padding:0 2px;";
    del.addEventListener("click", (e) => { e.stopPropagation(); deleteForm(f.id); });
    li.appendChild(span);
    li.appendChild(del);
    ul.appendChild(li);
  });
}

/** Delete a custom form (registry + config files). */
async function deleteForm(id) {
  if (!confirm("删除形态 " + id + "？将移除注册表条目与配置文件（正在使用该形态的玩家需先变回人形）。")) return;
  try {
    const r = await api("/api/delete-form", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ id }),
    });
    setStatus(r.deleted ? r.message : r.message, r.deleted);
    await loadForms();
  } catch (e) {
    setStatus("删除失败：" + e.message, false);
  }
}

async function loadExamples() {
  const ex = await api("/api/examples");
  const sel = $("exampleSelect");
  sel.innerHTML = "";
  const blank = document.createElement("option");
  blank.value = ""; blank.textContent = "— 选择一个原版形态 —";
  sel.appendChild(blank);
  ex.forEach((f) => {
    const opt = document.createElement("option");
    opt.value = f.id; opt.textContent = f.id;
    sel.appendChild(opt);
  });
  // fill the base_entity dropdown with registered latex entity ids
  try {
    const entities = await api("/api/entity-types");
    const dl = $("entityList");
    dl.innerHTML = "";
    entities.forEach((e) => {
      const opt = document.createElement("option");
      opt.value = e;
      dl.appendChild(opt);
    });
  } catch (e) { /* non-fatal */ }
}

/** Hot-register the current form without restarting the game. */
async function hotRegister() {
  const id = $("f_id").value.trim();
  if (!id) { $("saveResult").textContent = "✗ 请先填写 id"; return; }
  try {
    const r = await api("/api/hot-register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ id }),
    });
    $("saveResult").textContent = r.registered ? "✓ " + r.message : "✗ " + r.message;
    $("saveResult").style.color = r.registered ? "#9ecb5a" : "#e8705a";
    if (r.registered) await loadForms();
  } catch (e) {
    $("saveResult").textContent = "✗ " + e.message;
    $("saveResult").style.color = "#e8705a";
  }
}

async function selectForm(id) {
  state.currentId = id;
  state.selectedMesh = null;
  state.selectedCubeId = null;
  state.hoverMesh = null;
  state.editMode = false;
  document.querySelectorAll("#formList li").forEach((li) => {
    li.classList.toggle("active", li.textContent === id);
  });
  let f;
  try {
    f = await api("/api/forms/" + encodeURIComponent(id));
  } catch (e) {
    setStatus("加载 " + id + " 失败：" + e.message, false);
    return;
  }
  if (f.textureUrl) {
    $("texPreview").src = f.textureUrl;
  } else {
    $("texPreview").src = "";
  }
  fillForm(f);
  state.modelOffsetFrozen = null;
  if (typeof modelUndo !== "undefined") { modelUndo = []; modelRedo = []; }
  if (f.model && f.model.root) {
    if (typeof dedupeModelTree === "function") dedupeModelTree(f.model);
    if (typeof ensureCubeIds === "function") ensureCubeIds(f.model.root);
  }
  await renderModel(f.model || null);
  // load the saved glow layer AFTER rendering (renderModel's initTextureCanvas
  // resets the emissive canvas, so ordering matters)
  await loadEmissiveFor(f.id);
  if (typeof fillCubePane === "function") fillCubePane();
  setStatus("已加载：" + id + (f.model ? "" : "（无模型数据，进世界变身后自动生成）"));
}

/** Loads the saved glow texture (config textures/<id>_emissive.png) into the emissive layer. */
async function loadEmissiveFor(id) {
  emissiveCtx.clearRect(0, 0, texSize, texSize);
  if (emissiveTexture) emissiveTexture.needsUpdate = true;
  try {
    const res = await fetch("/api/forms/" + encodeURIComponent(id) + "/emissive.png");
    if (!res.ok) return;
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const img = new Image();
    await new Promise((resolve, reject) => {
      img.onload = resolve;
      img.onerror = reject;
      img.src = url;
    });
    URL.revokeObjectURL(url);
    emissiveCtx.clearRect(0, 0, texSize, texSize);
    emissiveCtx.drawImage(img, 0, 0);
    if (emissiveTexture) emissiveTexture.needsUpdate = true;
  } catch (e) { /* no glow layer saved */ }
}

function fillForm(f) {
  const def = f.definition || {};
  const app = f.appearance || {};
  $("f_id").value = def.id || f.id || "";
  $("f_base").value = def.base_entity || "";
  $("f_mode").value = def.transfur_mode || "replication";
  $("f_abilities").value = Array.isArray(def.abilities) ? def.abilities.join("\n") : "";
  $("f_properties").value = def.properties ? JSON.stringify(def.properties, null, 2) : "";
  $("f_tint").value = app.tint || "#ffffff";
  $("f_texture").value = app.texture || "";
  state.texPngB64 = null;
  state.modelJson = f.model || null;
  $("texPreview").src = f.textureUrl ? f.textureUrl : "";
}

function newForm() {
  if (!confirm("新建形态？将清空当前编辑内容（不影响已保存文件）。")) return;
  state.currentId = null;
  state.modelJson = null;
  state.texPngB64 = null;
  state.selectedMesh = null;
  state.selectedCubeId = null;
  state.editMode = false;
  $("f_id").value = "my_wolf";
  $("f_base").value = "changed:dark_latex_wolf_male";
  $("f_mode").value = "replication";
  $("f_abilities").value = "";
  $("f_properties").value = "";
  $("f_tint").value = "#ff3333";
  $("f_texture").value = "";
  $("texPreview").src = "";
  clearScene();
  setStatus("新建形态：填写后点「保存」；id 只能是小写字母/数字/下划线，且不能以数字开头。");
}

/* ---------------------------------------------------------------- form -> payload */
/** Load the registered ability ids (for validation + hint). */
async function loadAbilities() {
  try {
    state.abilityIds = await api("/api/abilities");
    const hint = document.getElementById("abilityHint");
    if (hint) {
      hint.textContent = "可用能力：" + state.abilityIds.join("、");
      hint.title = state.abilityIds.join("\n");
    }
  } catch (e) { /* non-fatal */ }
}

function collectDefinition() {
  const id = $("f_id").value.trim();
  if (!/^[a-z][a-z0-9_]{0,63}$/.test(id)) {
    throw new Error("id 只能是小写字母/数字/下划线，且不能以数字开头（如 my_wolf）");
  }
  const abilities = $("f_abilities").value.split("\n").map((s) => s.trim()).filter(Boolean);
  if (state.abilityIds.length && abilities.length) {
    const bad = abilities.filter((a) => !state.abilityIds.includes(a));
    if (bad.length) {
      throw new Error("未知能力：" + bad.join("、") + "（可用：见「能力」输入框下方提示）");
    }
  }
  let properties = {};
  const pt = $("f_properties").value.trim();
  if (pt) {
    try { properties = JSON.parse(pt); }
    catch (e) { throw new Error("properties 不是合法 JSON：" + e.message); }
  }
  const def = {
    id,
    base_entity: $("f_base").value.trim(),
    transfur_mode: $("f_mode").value,
  };
  if (abilities.length) def.abilities = abilities;
  if (Object.keys(properties).length) def.properties = properties;
  return def;
}

function collectAppearance() {
  const app = {};
  const tint = $("f_tint").value;
  if (tint && tint !== "#ffffff") app.tint = tint;
  const tex = $("f_texture").value.trim();
  if (tex) app.texture = tex;
  return app;
}

/* ---------------------------------------------------------------- save / export / import */
async function save() {
  let def, app;
  try {
    def = collectDefinition();
    app = collectAppearance();
  } catch (e) {
    $("saveResult").textContent = "✗ " + e.message;
    $("saveResult").style.color = "#e8705a";
    return;
  }
  const payload = { definition: def, appearance: app };
  if (state.modelJson) {
    if (typeof dedupeModelTree === "function") dedupeModelTree(state.modelJson);
    payload.model = typeof cloneModelJson === "function" ? cloneModelJson(state.modelJson) : state.modelJson;
  }
  if (state.texPngB64) payload.texturePng = state.texPngB64;
  else if (textureCanvas) {
    try { payload.texturePng = textureCanvas.toDataURL("image/png").split(",")[1]; } catch (e) { /* no edits */ }
  }
  // glow layer: export only if it has any opaque pixels
  if (emissiveCanvas) {
    const d = emissiveCtx.getImageData(0, 0, emissiveCanvas.width, emissiveCanvas.height).data;
    let has = false;
    for (let i = 3; i < d.length; i += 4) { if (d[i] > 0) { has = true; break; } }
    if (has) {
      try { payload.emissivePng = emissiveCanvas.toDataURL("image/png").split(",")[1]; } catch (e) { /* ignore */ }
    }
  }
  try {
    const r = await api("/api/save", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    $("saveResult").textContent = "✓ " + r.message;
    $("saveResult").style.color = "#9ecb5a";
    setStatus("已保存：" + def.id + "（" + (r.hotApplied || []).join("、") + " 热生效；" + ((r.needRestart || []).join("、") || "无") + " 需重启）");
    await loadForms();
  } catch (e) {
    $("saveResult").textContent = "✗ " + e.message;
    $("saveResult").style.color = "#e8705a";
  }
}

function exportFile() {
  let def, app;
  try {
    def = collectDefinition();
    app = collectAppearance();
  } catch (e) {
    alert(e.message);
    return;
  }
  const payload = {
    changedcreator_export: 1,
    id: def.id,
    definition: def,
    appearance: app,
    model: state.modelJson ? (typeof cloneModelJson === "function" ? cloneModelJson(state.modelJson) : state.modelJson) : null,
  };
  if (state.texPngB64) payload.texturePngBase64 = state.texPngB64;
  else if (textureCanvas) {
    try { payload.texturePngBase64 = textureCanvas.toDataURL("image/png").split(",")[1]; } catch (e) {}
  }
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = def.id + ".json";
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  setStatus("已导出 " + a.download + "（可放入游戏内 config/changedcreator/imports/ 导入）");
}

function readLocalFile(file, onJson) {
  const reader = new FileReader();
  reader.onload = () => {
    try { onJson(JSON.parse(reader.result)); }
    catch (e) { alert("文件解析失败：" + e.message); }
  };
  reader.readAsText(file);
}

function fillFormFromImport(payload) {
  if (!payload.changedcreator_export) { alert("不是编辑器导出文件"); return; }
  fillForm({
    definition: payload.definition || {},
    appearance: payload.appearance || {},
    model: payload.model || null,
  });
  if (payload.model) {
    state.modelJson = payload.model;
    if (payload.model.root && typeof ensureCubeIds === "function") ensureCubeIds(payload.model.root);
    renderModel(payload.model, false);
  }
  if (payload.texturePngBase64) {
    state.texPngB64 = payload.texturePngBase64;
    $("texPreview").src = "data:image/png;base64," + payload.texturePngBase64;
  }
  state.currentId = payload.id || null;
  setStatus("已读取导出文件：" + (payload.id || "") + "（点「保存」写入配置并热生效）");
}

function readPng(file) {
  const reader = new FileReader();
  reader.onload = () => {
    const b64 = reader.result.split(",")[1];
    state.texPngB64 = b64;
    $("texPreview").src = reader.result;
    // load into the editable texture canvas + refresh the 3D model right away
    const img = new Image();
    img.onload = () => {
      initTextureCanvas(img);
      renderModel(state.modelJson, false);
    };
    img.src = reader.result;
    const id = $("f_id").value.trim();
    if (id && !$("f_texture").value.trim()) {
      $("f_texture").value = "changedcreator:textures/entity/" + id + ".png";
    }
  };
  reader.readAsDataURL(file);
}

/* ---------------------------------------------------------------- three.js preview */
let scene, camera, renderer, controls, modelGroup;
let keyLight, fillLight;   // directional lights attached to the model group (fixed relative to model)
let raycaster, pointer;
let uvIndex = []; // {mesh, edges, uvMin, uvMax} for texture<->model sync
let textureCanvas = null, textureCtx = null, textureTexture = null;
let emissiveCanvas = null, emissiveCtx = null;   // glow layer (color = glow color)
let emissiveTexture = null;                       // CanvasTexture of the glow layer (model emissiveMap)
let overlayCanvas = null, overlayCtx = null;
let texSize = 96;
let texZoom = 3;                 // display pixels per texture pixel (wrap is 288px)
let undoStack = [], redoStack = [];
const UNDO_LIMIT = 40;
let mouseInPreview = false;      // pointer over the 3D preview (keyboard camera keys)
let texMouseIn = false;          // pointer over the texture viewport

function initSplitters() {
  function bind(splitId, leftId) {
    const split = $(splitId), left = $(leftId);
    if (!split || !left) return;
    split.addEventListener("pointerdown", (e) => {
      e.preventDefault();
      split.classList.add("dragging");
      const startX = e.clientX;
      const startW = left.getBoundingClientRect().width;
      const move = (ev) => {
        const w = Math.max(180, startW + (ev.clientX - startX));
        left.style.width = w + "px";
        left.style.flex = "0 0 " + w + "px";
        if (leftId === "previewPane" && renderer) {
          const el = $("three");
          const w = Math.max(1, el.clientWidth), h = Math.max(1, el.clientHeight);
          renderer.setSize(w, h);
          camera.aspect = w / h;
          camera.updateProjectionMatrix();
        }
      };
      const up = () => {
        split.classList.remove("dragging");
        window.removeEventListener("pointermove", move);
        window.removeEventListener("pointerup", up);
      };
      window.addEventListener("pointermove", move);
      window.addEventListener("pointerup", up);
    });
  }
  bind("splitPreview", "previewPane");
  bind("splitCube", "cubePane");
}

function initThree() {
  const el = $("three");
  scene = new THREE.Scene();
  camera = new THREE.PerspectiveCamera(50, 1, 0.1, 100);
  camera.position.set(2.2, 1.6, 2.6);
  camera.lookAt(0, 0.6, 0);
  state.camDefault = {
    pos: camera.position.clone(),
    target: new THREE.Vector3(0, 0.6, 0),
  };

  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(window.devicePixelRatio);
  renderer.setSize(Math.max(1, el.clientWidth), Math.max(1, el.clientHeight));
  renderer.domElement.style.display = "block";
  renderer.domElement.style.width = "100%";
  renderer.domElement.style.height = "100%";
  el.appendChild(renderer.domElement);

  controls = new THREE.OrbitControls(camera, renderer.domElement);
  controls.target.set(0, 0.6, 0);
  controls.enableDamping = true;
  controls.enableKeys = false;

  // track whether the pointer is over the 3D preview (keyboard camera controls only then)
  mouseInPreview = false;
  el.addEventListener("mouseenter", () => { mouseInPreview = true; });
  el.addEventListener("mouseleave", () => { mouseInPreview = false; });
  texMouseIn = false;
  const wrapEl = $("texCanvasWrap");
  wrapEl.addEventListener("mouseenter", () => { texMouseIn = true; });
  wrapEl.addEventListener("mouseleave", () => { texMouseIn = false; });
  wrapEl.addEventListener("scroll", updateSelBox);

  scene.add(new THREE.HemisphereLight(0xffffff, 0x404060, 0.45));
  // Lights ride with the model. Direction appears flipped (verified: -100 lights the top),
  // so "front-top" lighting is achieved from a back-bottom position.
  keyLight = new THREE.DirectionalLight(0xffffff, 1.4);
  keyLight.position.set(20, -100, 40);
  fillLight = new THREE.DirectionalLight(0x8899ff, 0.25);
  fillLight.position.set(0, 3, -3);

  scene.add(new THREE.GridHelper(4, 20, 0x445566, 0x334455));

  raycaster = new THREE.Raycaster();
  pointer = new THREE.Vector2();

  animate();
  window.addEventListener("resize", () => {
    renderer.setSize(el.clientWidth, el.clientHeight);
    camera.aspect = el.clientWidth / el.clientHeight;
    camera.updateProjectionMatrix();
  });
}

function animate() {
  requestAnimationFrame(animate);
  controls.update();
  updateMarkerOpacity();
  renderer.render(scene, camera);
}

function clearScene() {
  if (modelGroup) { scene.remove(modelGroup); modelGroup = null; }
  uvIndex = [];
  clearMarkers();
  const box = $("selBox");
  if (box) box.style.display = "none";
  $("previewHint").style.display = "none";
  $("previewMeta").textContent = "";
}

/** Rebuild the 3D model from the JSON model tree. */
async function renderModel(modelJson, forceTint, opts) {
  opts = opts || {};
  const keepSel = opts.keepSelection || state.selectedCubeId;
  const keepEdit = !!opts.keepEdit && state.editMode;
  clearScene();
  state.selectedMesh = null;
  state.hoverMesh = null;
  if (!opts.keepEdit) state.editMode = false;
  if (modelJson) state.modelJson = modelJson;
  if (!modelJson || !modelJson.root) {
    $("previewHint").style.display = "block";
    if (typeof fillCubePane === "function") fillCubePane();
    return;
  }
  try {
    const tint = $("f_tint").value || "#ffffff";
    const color = new THREE.Color(tint);
    let texture = null;
    if (!forceTint && textureTexture && textureCanvas && opts.reuseTexture === true) {
      texture = textureTexture;
    } else {
      const texSrc = $("texPreview").src;
      if (!forceTint && texSrc && (texSrc.startsWith("http") || texSrc.startsWith("data:"))) {
        texture = await loadTexture(texSrc);
      }
    }
    modelGroup = new THREE.Group();
    modelGroup.add(buildPart(modelJson.root, color, texture));
    modelGroup.rotation.y = Math.PI; // MC models face -Z; turn to camera
    if (!state.modelOffsetFrozen) {
      const box = new THREE.Box3().setFromObject(modelGroup);
      const center = box.getCenter(new THREE.Vector3());
      modelGroup.position.sub(center);
      const grounded = new THREE.Box3().setFromObject(modelGroup);
      modelGroup.position.y -= grounded.min.y;
      state.modelOffsetFrozen = modelGroup.position.clone();
    } else {
      modelGroup.position.copy(state.modelOffsetFrozen);
    }
    // lights ride with the model so they always come from the model's top-front
    if (keyLight) modelGroup.add(keyLight);
    if (fillLight) modelGroup.add(fillLight);
    scene.add(modelGroup);
    if (typeof attachGizmos === "function") attachGizmos();
    $("previewMeta").textContent = "模型部件数：" + countParts(modelJson.root)
      + " · 方块 " + countCubes(modelJson.root)
      + (texture ? "（已贴纹理：精确 UV）" : "（无贴图，用 tint 色）")
      + "    模式：模型编辑（点击选方块，E 进入贴图）";
    if (keepSel) {
      state.selectedCubeId = keepSel;
      state.selectedMesh = meshByCubeId(keepSel);
    }
    state.editMode = keepEdit;
    document.body.classList.toggle("edit-mode", state.editMode);
    updateHighlights();
    updateSelBox();
    if (typeof fillCubePane === "function") fillCubePane();
  } catch (e) {
    $("previewHint").style.display = "block";
    $("previewHint").textContent = "模型渲染失败：" + e.message;
  }
}

function renderModelFromState() {
  renderModel(state.modelJson, false, { keepSelection: state.selectedCubeId, keepEdit: state.editMode, reuseTexture: true });
}

function meshByCubeId(id) {
  if (!id || !modelGroup) return null;
  let found = null;
  modelGroup.traverse((o) => { if (o.isMesh && o.userData.cubeId === id) found = o; });
  return found;
}

function countCubes(node) {
  return (node.cubes || []).length + (node.children || []).reduce((s, c) => s + countCubes(c), 0);
}

function loadTexture(src) {
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () => {
      initTextureCanvas(img);
      resolve(textureTexture);
    };
    img.onerror = () => resolve(null);
    img.src = src;
  });
}

function buildPart(node, color, texture) {
  const g = new THREE.Group();
  g.position.set(node.pos[0] / 16, -node.pos[1] / 16, node.pos[2] / 16);
  g.rotation.set(-node.rot[0], node.rot[1], node.rot[2]);
  if (node.scale) g.scale.set(node.scale[0], node.scale[1], node.scale[2]);
  (node.cubes || []).forEach((c) => {
    const geo = buildCubeGeometry(c, texture);
    const mat = texture
      ? new THREE.MeshLambertMaterial({
          map: texture,
          side: THREE.DoubleSide,
          transparent: true,
          alphaTest: 0.01,
          emissive: 0xffffff,
          emissiveMap: emissiveTexture || undefined,
          emissiveIntensity: 1,
        })
      : new THREE.MeshLambertMaterial({ color: color.clone(), side: THREE.DoubleSide });
    const mesh = new THREE.Mesh(geo, mat);
    if (c.kind === "plane") {
      mat.polygonOffset = true;
      mat.polygonOffsetFactor = -1;
      mat.polygonOffsetUnits = -1;
      mat.depthWrite = false;
    }
    const cx = (c.min[0] + c.max[0]) / 2;
    const cy = (c.min[1] + c.max[1]) / 2;
    const cz = (c.min[2] + c.max[2]) / 2;
    if (c.faces && c.faces.length) {
      geo.translate(-cx / 16, cy / 16, -cz / 16);
    } else {
      mesh.position.set(0, 0, 0);
    }
    const rot = c.rot || [0, 0, 0];
    const pivot = new THREE.Group();
    pivot.position.set(cx / 16, -cy / 16, cz / 16);
    pivot.rotation.set(-(rot[0] || 0), rot[1] || 0, rot[2] || 0);
    const edges = new THREE.EdgesGeometry(geo);
    const line = new THREE.LineSegments(edges, new THREE.LineBasicMaterial({ color: 0x000000, transparent: true, opacity: 0.35, depthTest: false }));
    line.renderOrder = 10;
    mesh.userData.line = line;
    mesh.userData.cubeId = c.id;
    mesh.userData.cube = c;
    mesh.userData.part = node;
    line.userData.mesh = mesh;
    pivot.add(mesh);
    pivot.add(line);
    g.add(pivot);
    // record UV rectangles for texture<->model sync (local points; marker uses mesh.localToWorld)
    recordUvRects(mesh, c, texture, [cx, cy, cz]);
  });
  (node.children || []).forEach((child) => g.add(buildPart(child, color, texture)));
  return g;
}

/** Records each face's UV rectangle + corner 3D points for reverse lookup (texture pixel -> model faces). */
function recordUvRects(mesh, c, texture, center) {
  const faces = c.faces;
  if (!faces || !faces.length) return;
  const cx = center ? center[0] : 0, cy = center ? center[1] : 0, cz = center ? center[2] : 0;
  for (const face of faces) {
    if (!face.verts || face.verts.length !== 4) continue;
    let umin = 2, umax = -1, vmin = 2, vmax = -1;
    for (const v of face.verts) {
      umin = Math.min(umin, v.uv[0]); umax = Math.max(umax, v.uv[0]);
      vmin = Math.min(vmin, v.uv[1]); vmax = Math.max(vmax, v.uv[1]);
    }
    // corners ordered by UV: (umin,vmin) (umax,vmin) (umax,vmax) (umin,vmax)
    // stored in mesh-local space (geometry is centered on the cube)
    const corners = [];
    for (const t of [[0, 0], [1, 0], [1, 1], [0, 1]]) {
      const tu = umin + t[0] * (umax - umin);
      const tv = vmin + t[1] * (vmax - vmin);
      let best = 0, bd = 1e9;
      for (let i = 0; i < 4; i++) {
        const du = face.verts[i].uv[0] - tu;
        const dv = face.verts[i].uv[1] - tv;
        const d = du * du + dv * dv;
        if (d < bd) { bd = d; best = i; }
      }
      const p = face.verts[best].p;
      corners.push(new THREE.Vector3((p[0] - cx) / 16, -(p[1] - cy) / 16, (p[2] - cz) / 16));
    }
    uvIndex.push({ mesh, uvMin: [umin, vmin], uvMax: [umax, vmax], facePts: corners });
  }
}

function buildCubeGeometry(c, texture) {
  const faces = c.faces;
  if (faces && faces.length) {
    const positions = [], uvs = [], normals = [], indices = [];
    let vi = 0;
    for (const face of faces) {
      const vs = face.verts;
      if (!vs || vs.length !== 4) continue;
      for (const qi of [0, 1, 2, 0, 2, 3]) {
        const v = vs[qi];
        positions.push(v.p[0] / 16, -v.p[1] / 16, v.p[2] / 16);
        uvs.push(v.uv[0], v.uv[1]);
        const n = face.normal || [0, 1, 0];
        // Editor-created (uvLayout) faces wind opposite to extracted ones — their
        // normals point inward, which showed as inverted lighting in the preview.
        const flip = c.uvLayout ? -1 : 1;
        normals.push(n[0] * flip, -n[1] * flip, n[2] * flip);
        indices.push(vi++);
      }
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute("position", new THREE.Float32BufferAttribute(positions, 3));
    geo.setAttribute("uv", new THREE.Float32BufferAttribute(uvs, 2));
    geo.setAttribute("normal", new THREE.Float32BufferAttribute(normals, 3));
    geo.setIndex(indices);
    return geo;
  }
  const w = Math.max(1e-4, Math.abs(c.max[0] - c.min[0]) / 16);
  const h = Math.max(1e-4, Math.abs(c.max[1] - c.min[1]) / 16);
  const d = Math.max(1e-4, Math.abs(c.max[2] - c.min[2]) / 16);
  return new THREE.BoxGeometry(w, h, d);
}

function countParts(node) {
  return 1 + (node.children || []).reduce((s, c) => s + countParts(c), 0);
}

/* ---------------------------------------------------------------- texture editor */
function initTexEditor() {
  textureCanvas = document.createElement("canvas");
  textureCanvas.width = texSize;
  textureCanvas.height = texSize;
  textureCtx = textureCanvas.getContext("2d");
  textureCtx.imageSmoothingEnabled = false;
  textureCtx.clearRect(0, 0, texSize, texSize);

  emissiveCanvas = document.createElement("canvas");
  emissiveCanvas.width = texSize;
  emissiveCanvas.height = texSize;
  emissiveCtx = emissiveCanvas.getContext("2d");
  emissiveCtx.imageSmoothingEnabled = false;
  emissiveCtx.clearRect(0, 0, texSize, texSize);

  overlayCanvas = $("texOverlay");
  overlayCtx = overlayCanvas.getContext("2d");
  overlayCtx.imageSmoothingEnabled = false;

  const display = $("texCanvas");
  display.width = texSize;
  display.height = texSize;
  const dctx = display.getContext("2d");
  dctx.imageSmoothingEnabled = false;
  setInterval(syncTexDisplay, 80);

  display.addEventListener("mousemove", (e) => {
    const [px, py] = canvasPixel(e);
    if (e.buttons === 1) canvasEdit(px, py, false);
    syncOverlayFromTex(px, py);
    highlightModelFromTex(px, py);
  });
  display.addEventListener("mousedown", (e) => {
    const [px, py] = canvasPixel(e);
    canvasEdit(px, py, true);
  });
  display.addEventListener("wheel", (e) => {
    e.preventDefault();
    texZoom = Math.max(2, Math.min(16, texZoom + (e.deltaY < 0 ? 1 : -1)));
    applyTexZoom();
  }, { passive: false });
  syncColorUI();
  applyTexZoom();
}

function applyTexZoom() {
  const px = texSize * texZoom;
  // texInner (relative container inside the scrolling wrap) gets the scaled size,
  // so canvas/overlay/selBox all scroll together with the texture.
  $("texInner").style.width = px + "px";
  $("texInner").style.height = px + "px";
  $("texCanvas").style.width = px + "px";
  $("texCanvas").style.height = px + "px";
  $("texOverlay").style.width = px + "px";
  $("texOverlay").style.height = px + "px";
  updateSelBox();
}

function canvasPixel(e) {
  const r = $("texCanvas").getBoundingClientRect();
  const x = Math.floor((e.clientX - r.left) / r.width * texSize);
  const y = Math.floor((e.clientY - r.top) / r.height * texSize);
  return [Math.max(0, Math.min(texSize - 1, x)), Math.max(0, Math.min(texSize - 1, y))];
}

function initTextureCanvas(img) {
  const w = img.width, h = img.height;
  texSize = Math.max(w, h);
  textureCanvas.width = texSize;
  textureCanvas.height = texSize;
  textureCtx = textureCanvas.getContext("2d");
  textureCtx.imageSmoothingEnabled = false;
  textureCtx.clearRect(0, 0, texSize, texSize);
  textureCtx.drawImage(img, 0, 0, w, h);
  emissiveCanvas.width = texSize;
  emissiveCanvas.height = texSize;
  emissiveCtx = emissiveCanvas.getContext("2d");
  emissiveCtx.imageSmoothingEnabled = false;
  emissiveCtx.clearRect(0, 0, texSize, texSize);
  $("texCanvas").width = texSize;
  $("texCanvas").height = texSize;
  $("texOverlay").width = texSize;
  $("texOverlay").height = texSize;
  overlayCtx = $("texOverlay").getContext("2d");
  overlayCtx.imageSmoothingEnabled = false;
  if (textureTexture) textureTexture.dispose();
  textureTexture = new THREE.CanvasTexture(textureCanvas);
  textureTexture.flipY = false;
  textureTexture.magFilter = THREE.NearestFilter;
  if (emissiveTexture) emissiveTexture.dispose();
  emissiveTexture = new THREE.CanvasTexture(emissiveCanvas);
  emissiveTexture.flipY = false;
  emissiveTexture.magFilter = THREE.NearestFilter;
  undoStack = []; redoStack = [];
  applyTexZoom();
}

function setTool(t) {
  state.tool = t;
  ["toolBrush", "toolFill", "toolPick"].forEach((id) => $("tool" + id.slice(4)).classList.toggle("active", id === ("tool" + t[0].toUpperCase() + t.slice(1))));
}

function currentColor() {
  return [
    parseInt($("cR").value), parseInt($("cG").value),
    parseInt($("cB").value), parseInt($("cA").value),
  ];
}

function layerCanvas() { return state.layer === "emissive" ? emissiveCanvas : textureCanvas; }
function layerCtx() { return state.layer === "emissive" ? emissiveCtx : textureCtx; }

/** Mirrors the current layers onto the visible texture canvas (base + glow overlay). */
function syncTexDisplay() {
  const dctx = $("texCanvas").getContext("2d");
  dctx.clearRect(0, 0, texSize, texSize);
  if (state.layer === "emissive") {
    // editing glow: base at 50% as reference, glow overlaid full
    dctx.globalAlpha = 0.5;
    dctx.drawImage(textureCanvas, 0, 0);
    dctx.globalAlpha = 1;
    dctx.drawImage(emissiveCanvas, 0, 0);
  } else {
    dctx.drawImage(textureCanvas, 0, 0);
    if (emissiveCanvas) {
      dctx.globalAlpha = 0.7;
      dctx.drawImage(emissiveCanvas, 0, 0);
      dctx.globalAlpha = 1;
    }
  }
}

function toggleEmissive() {
  state.layer = state.layer === "emissive" ? "base" : "emissive";
  $("btnEmissive").classList.toggle("active", state.layer === "emissive");
  $("btnEmissive").title = state.layer === "emissive"
    ? "正在编辑发光层（发光颜色=像素颜色），再点返回普通层"
    : "切换到发光层（荧光区域用任意颜色涂抹）";
  setStatus(state.layer === "emissive" ? "发光层：发光颜色=所画像素颜色（模型预览实时显示）" : "普通层");
  syncTexDisplay();
  if (emissiveTexture) emissiveTexture.needsUpdate = true;
  if (state.layer === "base" && textureTexture) textureTexture.needsUpdate = true;
}

function canvasEdit(px, py, pushHistory) {
  if (!layerCanvas()) return;
  if (pushHistory || (state.tool === "fill" && !state.painting)) pushUndo();
  if (state.tool === "brush") {
    drawBrush(px, py, currentColor(), parseInt($("brushSize").value));
  } else if (state.tool === "fill") {
    floodFill(px, py, currentColor());
  } else if (state.tool === "pick") {
    const d = layerCtx().getImageData(px, py, 1, 1).data;
    $("cR").value = d[0]; $("cG").value = d[1]; $("cB").value = d[2]; $("cA").value = d[3];
    syncColorUI();
    setTool("brush");
    return;
  }
  if (state.layer === "base") textureNeedsUpdate();
  else if (emissiveTexture) emissiveTexture.needsUpdate = true;
}

function snapshotTex() {
  return { layer: state.layer, img: layerCtx().getImageData(0, 0, layerCanvas().width, layerCanvas().height) };
}
function restoreTex(snap) {
  const c = snap.layer === "emissive" ? emissiveCtx : textureCtx;
  c.putImageData(snap.img, 0, 0);
  if (snap.layer === "base") textureNeedsUpdate();
}
function pushUndo() {
  if (!textureCanvas) return;
  undoStack.push(snapshotTex());
  if (undoStack.length > UNDO_LIMIT) undoStack.shift();
  redoStack = [];
}
function undo() {
  if (!undoStack.length) return;
  redoStack.push(snapshotTex());
  restoreTex(undoStack.pop());
}
function redo() {
  if (!redoStack.length) return;
  undoStack.push(snapshotTex());
  restoreTex(redoStack.pop());
}
function exportTexture() {
  if (!textureCanvas) return;
  const a = document.createElement("a");
  a.href = textureCanvas.toDataURL("image/png");
  a.download = ($("f_id").value.trim() || "texture") + ".png";
  a.click();
}

/** Pixel brush: a perfect square of the given size, no anti-aliasing. */
function drawBrush(cx, cy, rgba, size) {
  const r = Math.floor(size / 2);
  const ctx = layerCtx();
  ctx.save();
  ctx.globalCompositeOperation = "source-over";
  ctx.fillStyle = `rgba(${rgba[0]},${rgba[1]},${rgba[2]},${rgba[3] / 255})`;
  ctx.fillRect(Math.floor(cx) - r, Math.floor(cy) - r, size, size);
  ctx.restore();
}

function floodFill(sx, sy, rgba) {
  const cv = layerCanvas();
  const ctx = layerCtx();
  const w = cv.width, h = cv.height;
  const img = ctx.getImageData(0, 0, w, h);
  const data = img.data;
  const start = (sy * w + sx) * 4;
  const target = [data[start], data[start + 1], data[start + 2], data[start + 3]];
  const fill = rgba;
  if (target[0] === fill[0] && target[1] === fill[1] && target[2] === fill[2] && target[3] === fill[3]) return;
  const stack = [[sx, sy]];
  const seen = new Uint8Array(w * h);
  while (stack.length) {
    const [x, y] = stack.pop();
    if (x < 0 || y < 0 || x >= w || y >= h) continue;
    const i = y * w + x;
    if (seen[i]) continue;
    seen[i] = 1;
    const j = i * 4;
    if (Math.abs(data[j] - target[0]) > 24 || Math.abs(data[j + 1] - target[1]) > 24 ||
        Math.abs(data[j + 2] - target[2]) > 24 || Math.abs(data[j + 3] - target[3]) > 24) continue;
    data[j] = fill[0]; data[j + 1] = fill[1]; data[j + 2] = fill[2]; data[j + 3] = fill[3];
    stack.push([x + 1, y], [x - 1, y], [x, y + 1], [x, y - 1]);
  }
  ctx.putImageData(img, 0, 0);
}

function textureNeedsUpdate() {
  if (textureTexture) textureTexture.needsUpdate = true;
  if (state.texPngB64 === null && $("f_texture").value.trim()) {
    // editing the loaded texture -> mark it as an export candidate on save
  }
}

function syncColorFromSliders() {
  const [r, g, b] = [parseInt($("cR").value), parseInt($("cG").value), parseInt($("cB").value)];
  $("cHex").value = "#" + [r, g, b].map((v) => v.toString(16).padStart(2, "0")).join("");
  $("colorPrev").style.background = `rgba(${r},${g},${b},${parseInt($("cA").value) / 255})`;
}
function syncColorFromHex() {
  let h = $("cHex").value.trim().replace(/^#/, "");
  if (h.length === 3) h = h.split("").map((c) => c + c).join("");
  if (!/^[0-9a-fA-F]{6}$/.test(h)) return;
  $("cR").value = parseInt(h.slice(0, 2), 16);
  $("cG").value = parseInt(h.slice(2, 4), 16);
  $("cB").value = parseInt(h.slice(4, 6), 16);
  syncColorFromSliders();
}
function syncColorUI() {
  syncColorFromSliders();
}

function hsvToRgb(h, s, v) {
  const i = Math.floor(h * 6);
  const f = h * 6 - i;
  const p = v * (1 - s);
  const q = v * (1 - f * s);
  const t = v * (1 - (1 - f) * s);
  const m = i % 6;
  const r = [v, q, p, p, t, v][m];
  const g = [t, v, v, q, p, p][m];
  const b = [p, p, t, v, v, q][m];
  return [Math.round(r * 255), Math.round(g * 255), Math.round(b * 255)];
}

function initColorWheel() {
  const cv = $("colorWheel");
  if (!cv) return;
  const ctx = cv.getContext("2d");
  const w = cv.width, h = cv.height, cx = w / 2, cy = h / 2, R = Math.min(cx, cy) - 1;
  const img = ctx.createImageData(w, h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const dx = x - cx, dy = y - cy;
      const dist = Math.sqrt(dx * dx + dy * dy);
      const i = (y * w + x) * 4;
      if (dist > R) { img.data[i + 3] = 0; continue; }
      const hue = (Math.atan2(dy, dx) / Math.PI + 1) / 2;
      // White at center -> saturated color at mid ring -> black at outer edge
      const t = dist / R;
      let sat, val;
      if (t < 0.5) { sat = t * 2; val = 1; }
      else { sat = 1; val = 1 - (t - 0.5) * 2; }
      const [r, g, b] = hsvToRgb(hue, sat, val);
      img.data[i] = r; img.data[i + 1] = g; img.data[i + 2] = b; img.data[i + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
  const pick = (e) => {
    const r = cv.getBoundingClientRect();
    const x = Math.floor((e.clientX - r.left) / r.width * w);
    const y = Math.floor((e.clientY - r.top) / r.height * h);
    const d = ctx.getImageData(Math.max(0, Math.min(w - 1, x)), Math.max(0, Math.min(h - 1, y)), 1, 1).data;
    if (d[3] < 128) return;
    $("cR").value = d[0]; $("cG").value = d[1]; $("cB").value = d[2];
    syncColorUI();
  };
  cv.addEventListener("mousedown", pick);
  cv.addEventListener("mousemove", (e) => { if (e.buttons === 1) pick(e); });
}

/** Zoom/pan the texture view so the selected cube's UV region is in frame. */
function syncTexViewToMesh(mesh) {
  const recs = uvIndex.filter((r) => r.mesh === mesh);
  if (!recs.length) return;
  let umin = 1, umax = 0, vmin = 1, vmax = 0;
  recs.forEach((r) => {
    umin = Math.min(umin, r.uvMin[0]); umax = Math.max(umax, r.uvMax[0]);
    vmin = Math.min(vmin, r.uvMin[1]); vmax = Math.max(vmax, r.uvMax[1]);
  });
  const px0 = Math.floor(umin * texSize), py0 = Math.floor(vmin * texSize);
  const px1 = Math.ceil(umax * texSize), py1 = Math.ceil(vmax * texSize);
  const span = Math.max(8, px1 - px0, py1 - py0);
  texZoom = Math.max(3, Math.min(16, Math.floor(288 / span)));
  applyTexZoom();
  const wrap = $("texCanvasWrap");
  wrap.scrollLeft = Math.max(0, px0 * texZoom - 24);
  wrap.scrollTop = Math.max(0, py0 * texZoom - 24);
}

/** Outline each face polygon of the selected cube in the texture viewport (supports non-rectangular UV nets). */
function updateSelBox() {
  const box = $("selBox");
  const svg = $("selSvg");
  if (!box || !svg) return;
  if (!state.selectedMesh) { box.style.display = "none"; svg.innerHTML = ""; return; }
  const recs = uvIndex.filter((r) => r.mesh === state.selectedMesh);
  if (!recs.length) { box.style.display = "none"; svg.innerHTML = ""; return; }
  let umin = 1, umax = 0, vmin = 1, vmax = 0;
  recs.forEach((r) => {
    umin = Math.min(umin, r.uvMin[0]); umax = Math.max(umax, r.uvMax[0]);
    vmin = Math.min(vmin, r.uvMin[1]); vmax = Math.max(vmax, r.uvMax[1]);
  });
  box.style.display = "block";
  // the SVG spans the whole texInner (already sized by applyTexZoom), so only the viewport itself is positioned.
  box.style.left = "0px"; box.style.top = "0px";
  box.style.width = "100%"; box.style.height = "100%";
  // draw every face as its own polygon (scaled to texZoom); corners in UV space.
  let html = "";
  recs.forEach((r) => {
    const pts = r.facePts; // Vector3[] 4 corners in MC local px? Use UV rect instead for exactness.
    // Prefer per-face UV rectangle corners (umin/umax/vmin/vmax) — already computed.
    const x0 = r.uvMin[0] * texSize * texZoom, y0 = r.uvMin[1] * texSize * texZoom;
    const x1 = r.uvMax[0] * texSize * texZoom, y1 = r.uvMax[1] * texSize * texZoom;
    html += `<polygon points="${x0},${y0} ${x1},${y0} ${x1},${y1} ${x0},${y1}"/>`;
  });
  svg.innerHTML = html;
}

/** Highlight the texture pixel under (px,py) with inverted color of that pixel. */
function syncOverlayFromTex(px, py) {
  overlayCtx.clearRect(0, 0, texSize, texSize);
  let r = 255, g = 255, b = 255;
  try {
    const d = layerCtx().getImageData(px, py, 1, 1).data;
    r = 255 - d[0]; g = 255 - d[1]; b = 255 - d[2];
  } catch (e) { /* ignore */ }
  overlayCtx.fillStyle = `rgb(${r},${g},${b})`;
  overlayCtx.fillRect(px, py, 1, 1);
}

let uvMarkers = [];
function clearMarkers() {
  uvMarkers.forEach((m) => scene.remove(m));
  uvMarkers = [];
}
function markerAt(pos) {
  const m = new THREE.Mesh(
    new THREE.BoxGeometry(0.045, 0.045, 0.045),
    new THREE.MeshBasicMaterial({ color: 0xff00ff, transparent: true, opacity: 1, depthTest: false, depthWrite: false })
  );
  m.renderOrder = 999;
  m.position.copy(pos);
  scene.add(m);
  uvMarkers.push(m);
  return m;
}

/** Per-frame: markers directly visible to the camera are opaque; hidden behind model cubes -> 0.2. */
function updateMarkerOpacity() {
  if (!uvMarkers.length || !modelGroup) return;
  const camPos = camera.position;
  const dir = new THREE.Vector3();
  const r = new THREE.Raycaster();
  for (const m of uvMarkers) {
    dir.subVectors(m.position, camPos);
    const dist = dir.length();
    if (dist < 1e-4) { m.material.opacity = 1; continue; }
    dir.normalize();
    r.set(camPos, dir);
    r.far = dist - 0.01;
    const hits = r.intersectObject(modelGroup, true).filter((h) => h.object.isMesh);
    m.material.opacity = hits.length ? 0.2 : 1;
  }
}

/**
 * Highlight ALL model faces whose UV rect contains the hovered texture pixel,
 * placing one marker per face at the exact 3D spot (bilinear in the face).
 * Markers show through the model (semi-transparent) via depthTest:false.
 */
function highlightModelFromTex(px, py) {
  const u = (px + 0.5) / texSize;
  const v = (py + 0.5) / texSize;
  clearMarkers();
  for (const rec of uvIndex) {
    const on = u >= rec.uvMin[0] && u <= rec.uvMax[0] && v >= rec.uvMin[1] && v <= rec.uvMax[1];
    const line = rec.mesh.userData.line;
    if (line) {
      line.material.color.set(on ? 0x00ffcc : 0x000000);
      line.material.opacity = on ? 0.9 : 0.35;
    }
    if (on && rec.facePts) {
      const fu = rec.uvMax[0] === rec.uvMin[0] ? 0 : (u - rec.uvMin[0]) / (rec.uvMax[0] - rec.uvMin[0]);
      const fv = rec.uvMax[1] === rec.uvMin[1] ? 0 : (v - rec.uvMin[1]) / (rec.uvMax[1] - rec.uvMin[1]);
      const top = new THREE.Vector3().lerpVectors(rec.facePts[0], rec.facePts[1], fu);
      const bot = new THREE.Vector3().lerpVectors(rec.facePts[3], rec.facePts[2], fu);
      const p = top.lerp(bot, fv);
      markerAt(rec.mesh.localToWorld(p));
    }
  }
}

/* ---------------------------------------------------------------- model interaction */
function initModelInteraction() {
  const el = renderer.domElement;
  el.addEventListener("mousemove", onModelMove);
  el.addEventListener("click", onModelClick);
  // Only intercept when a gizmo is actually hit — otherwise OrbitControls must receive the event.
  el.addEventListener("pointerdown", onGizmoCapture, true);
  el.addEventListener("mousedown", onPaintDown);
  window.addEventListener("mouseup", onModelPointerUp);
  window.addEventListener("pointerup", onModelPointerUp);
  window.addEventListener("pointermove", onGizmoDrag);
}

function modelPointer(e) {
  const r = renderer.domElement.getBoundingClientRect();
  pointer.x = ((e.clientX - r.left) / r.width) * 2 - 1;
  pointer.y = -((e.clientY - r.top) / r.height) * 2 + 1;
}

function raycastModel() {
  if (!modelGroup) return [];
  raycaster.setFromCamera(pointer, camera);
  // Only meshes are pickable - LineSegments (edge highlights) must not intercept.
  return raycaster.intersectObject(modelGroup, true).filter((h) => h.object.isMesh);
}

function onModelMove(e) {
  if (gizmoDrag) return;
  modelPointer(e);
  const hits = raycastModel();
  const hit = hits.length ? hits[0] : null;
  const mesh = hit ? hit.object : null;
  if (mesh !== state.hoverMesh) {
    state.hoverMesh = mesh;
    updateHighlights();
  }
  // sync the hovered face UV to the texture overlay
  if (hit && hit.uv && textureCanvas) {
    const px = Math.floor(hit.uv.x * texSize);
    const py = Math.floor(hit.uv.y * texSize);
    syncOverlayFromTex(px, py);
  } else {
    overlayCtx.clearRect(0, 0, texSize, texSize);
  }
}

function onModelClick() {
  if (gizmoDrag || gizmoClickSuppress) { gizmoClickSuppress = false; return; }
  if (state.editMode) return; // in edit mode, painting takes over
  modelPointer(event);
  const hits = raycastModel();
  if (!hits.length) {
    state.selectedMesh = null;
    state.selectedCubeId = null;
    updateHighlights();
    updateSelBox();
    if (typeof fillCubePane === "function") fillCubePane();
    return;
  }
  // Click-through: clicking the same spot again selects the NEXT cube inside
  // (large cubes wrapping smaller ones) instead of re-selecting the outer one.
  const idx = state.selectedMesh ? hits.findIndex((h) => h.object === state.selectedMesh) : -1;
  const next = idx >= 0 && idx + 1 < hits.length ? hits[idx + 1] : hits[0];
  if (event.ctrlKey || event.metaKey) {
    // Ctrl/⌘ only: clicking an unselected block selects it; clicking a selected one deselects.
    const id = next.object.userData.cubeId || null;
    if (id) {
      const pos = state.selection.indexOf(id);
      if (pos >= 0) state.selection.splice(pos, 1);
      else state.selection.push(id);
      // keep the last toggled as the primary (drives the pane / gizmo)
      state.selectedCubeId = id;
      state.selectedMesh = meshByCubeId(id);
    }
  } else {
    state.selection = [];
    state.selectedMesh = next.object;
    state.selectedCubeId = next.object.userData.cubeId || null;
  }
  updateHighlights();
  updateSelBox();
  if (typeof fillCubePane === "function") fillCubePane();
}

function onGizmoCapture(e) {
  modelPointer(e);
  if (tryGizmoDown(e)) {
    e.preventDefault();
    e.stopPropagation();
    if (e.stopImmediatePropagation) e.stopImmediatePropagation();
  }
}

function onPaintDown(e) {
  if (gizmoDrag) return;
  if (!state.editMode || state.tool === "pick") return;
  modelPointer(e);
  const hits = raycastModel();
  const hit = hits.length ? hits[0] : null;
  if (!hit || !hit.uv) return;
  const mesh = hit.object;
  if (state.selectedMesh && mesh !== state.selectedMesh) return;
  const px = Math.floor(hit.uv.x * texSize);
  const py = Math.floor(hit.uv.y * texSize);
  canvasEdit(px, py, true);
  state.painting = true;
}

function onModelPointerUp(e) {
  state.painting = false;
  // A zero-move drag = click: step one increment along the axis (Alt reverses).
  if (gizmoDrag && !gizmoDrag.moved) {
    const axis = gizmoDrag.axis;
    const reverse = !!(e && (e.altKey || e.ctrlKey || e.metaKey));
    stepGizmo(axis, reverse);
    gizmoDrag.dirty = true;
  }
  endGizmoDrag();
}

function updateHighlights() {
  if (!modelGroup) return;
  const multi = new Set(state.selection || []);
  modelGroup.traverse((obj) => {
    if (!obj.isMesh || !obj.material) return;
    const id = obj.userData.cubeId;
    const inMulti = multi.has(id);
    const isSel = obj === state.selectedMesh;
    const isHov = obj === state.hoverMesh;
    obj.material.transparent = true;
    if (state.editMode) {
      obj.material.opacity = isSel ? 1 : 0.15;
    } else {
      obj.material.opacity = 1;
    }
    const line = obj.userData.line;
    if (line) {
      const active = isSel || isHov || inMulti;
      line.visible = active;
      line.material.color.set(isSel ? 0xffcc00 : inMulti ? 0xff8800 : isHov ? 0x00ffcc : 0x000000);
      line.material.opacity = 1;
      line.material.depthTest = false;
    }
  });
}

function toggleEditMode() {
  if (!state.selectedMesh) {
    setStatus("先点击选中一个方块，再按 E 进入编辑", false);
    return;
  }
  state.editMode = !state.editMode;
  document.body.classList.toggle("edit-mode", state.editMode);
  updateHighlights();
  if (state.editMode) syncTexViewToMesh(state.selectedMesh);
  setStatus(state.editMode ? "编辑模式：画笔/填充作用于选中方块；再按 E 退出" : "已退出编辑模式");
}

/* ---------------------------------------------------------------- keyboard */
function initKeyboard() {
  window.addEventListener("keydown", (e) => {
    const typing = e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement;
    if (typing) return; // never hijack keys while typing in a field

    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "z") {
      e.preventDefault();
      if (e.shiftKey) {
        if (typeof redoModelOrTex === "function") redoModelOrTex(); else redo();
      } else {
        if (typeof undoModelOrTex === "function") undoModelOrTex(); else undo();
      }
      return;
    }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "y") {
      e.preventDefault();
      if (typeof redoModelOrTex === "function") redoModelOrTex(); else redo();
      return;
    }
    if (!e.ctrlKey && !e.metaKey && !e.altKey) {
      const k = e.key.toLowerCase();
      if (k === "g") { toggleGizmoMode("move"); return; }
      if (k === "r") { toggleGizmoMode("rotate"); return; }
      if (k === "s") { toggleGizmoMode("scale"); return; }
    }

    if (e.key === "e" || e.key === "E") { if (mouseInPreview) toggleEditMode(); return; }
    if (e.key === "u" || e.key === "U") {
      if (!mouseInPreview) return;
      camera.position.copy(state.camDefault.pos);
      controls.target.copy(state.camDefault.target);
      controls.update();
      return;
    }
    const arrows = { ArrowUp: 1, ArrowDown: -1, ArrowLeft: 1, ArrowRight: -1 };
    if (arrows[e.key] !== undefined) {
      if (mouseInPreview) {
        movePreviewCamera(e.key, arrows[e.key]);
        e.preventDefault();
      } else if (texMouseIn) {
        panTexView(e.key);
        e.preventDefault();
      }
    }
  });
}

function movePreviewCamera(key, dir) {
  const step = 0.15;
  const right = new THREE.Vector3().crossVectors(camera.getWorldDirection(new THREE.Vector3()), camera.up).normalize();
  let d;
  if (key === "ArrowUp" || key === "ArrowDown") {
    d = new THREE.Vector3(0, dir * step, 0); // up/down = vertical
  } else {
    d = right.multiplyScalar(dir * step);    // left/right = horizontal
  }
  camera.position.add(d);
  controls.target.add(d);
}

function panTexView(key) {
  const wrap = $("texCanvasWrap");
  const step = 24;
  if (key === "ArrowUp") wrap.scrollTop -= step;
  else if (key === "ArrowDown") wrap.scrollTop += step;
  else if (key === "ArrowLeft") wrap.scrollLeft -= step;
  else if (key === "ArrowRight") wrap.scrollLeft += step;
}

/* ---------------------------------------------------------------- gizmos (move / rotate / scale) */
let gizmoGroup = null;
let gizmoDrag = null;
let gizmoClickSuppress = false;

let mirrorCenterMesh = null;
function drawMirrorCenter() {
  if (mirrorCenterMesh) { if (mirrorCenterMesh.parent) mirrorCenterMesh.parent.remove(mirrorCenterMesh); mirrorCenterMesh = null; }
  const { cube } = typeof selectedCube === "function" ? selectedCube() : { cube: null };
  if (!cube || !cube.group || !modelGroup) return;
  const origin = typeof originPointRoot === "function" ? originPointRoot() : [0, 0, 0];
  mirrorCenterMesh = new THREE.Mesh(
    new THREE.SphereGeometry(0.04, 8, 8),
    new THREE.MeshBasicMaterial({ color: 0xffff00, transparent: true, opacity: 0.8, depthTest: false, depthWrite: false })
  );
  // Attach to modelGroup so it follows the model's centering + rotation.y=PI.
  mirrorCenterMesh.position.set(origin[0] / 16, -origin[1] / 16, origin[2] / 16);
  mirrorCenterMesh.renderOrder = 998;
  modelGroup.add(mirrorCenterMesh);
}

function attachGizmos() {
  if (gizmoGroup && gizmoGroup.parent) gizmoGroup.parent.remove(gizmoGroup);
  gizmoGroup = new THREE.Group();
  gizmoGroup.name = "gizmos";
  scene.add(gizmoGroup);
  updateGizmos();
}

function axisArrow(dir, color, kind) {
  const g = new THREE.Group();
  const mat = new THREE.MeshBasicMaterial({ color, depthTest: false, transparent: true, opacity: 0.95 });
  const hitMat = new THREE.MeshBasicMaterial({ color, depthTest: false, transparent: true, opacity: 0.01 });
  if (kind === "rotate") {
    const tor = new THREE.Mesh(new THREE.TorusGeometry(0.42, 0.028, 8, 48), mat);
    const hit = new THREE.Mesh(new THREE.TorusGeometry(0.42, 0.08, 8, 32), hitMat);
    if (dir === "x") { tor.rotation.y = Math.PI / 2; hit.rotation.y = Math.PI / 2; }
    if (dir === "y") { tor.rotation.x = Math.PI / 2; hit.rotation.x = Math.PI / 2; }
    g.add(tor); g.add(hit);
  } else {
    const len = kind === "scale" ? 0.5 : 0.62;
    const shaft = new THREE.Mesh(new THREE.CylinderGeometry(0.018, 0.018, len, 8), mat);
    const tip = new THREE.Mesh(
      kind === "scale" ? new THREE.BoxGeometry(0.07, 0.07, 0.07) : new THREE.ConeGeometry(0.045, 0.11, 10),
      mat
    );
    const hit = new THREE.Mesh(new THREE.CylinderGeometry(0.07, 0.07, len + 0.16, 8), hitMat);
    shaft.position.y = len / 2;
    tip.position.y = len + 0.05;
    hit.position.y = (len + 0.16) / 2;
    g.add(shaft); g.add(tip); g.add(hit);
    if (dir === "x") g.rotation.z = -Math.PI / 2;
    if (dir === "z") g.rotation.x = Math.PI / 2;
  }
  g.userData.gizmoAxis = dir;
  g.userData.gizmoKind = kind;
  g.renderOrder = 20;
  g.traverse((o) => {
    if (o.isMesh) {
      o.renderOrder = 20;
      o.userData.gizmoAxis = dir;
      o.userData.gizmoKind = kind;
    }
  });
  return g;
}

function updateGizmos() {
  if (!gizmoGroup) return;
  while (gizmoGroup.children.length) gizmoGroup.remove(gizmoGroup.children[0]);
  const mesh = state.selectedMesh;
  const mode = state.gizmoMode;
  if (!mesh || state.editMode || !mode) { gizmoGroup.visible = false; return; }
  gizmoGroup.visible = true;
  const colors = { x: 0xe74c3c, y: 0x2ecc71, z: 0x3498db };
  ["x", "y", "z"].forEach((ax) => gizmoGroup.add(axisArrow(ax, colors[ax], mode)));
  // Pivot in WORLD space: last selected mesh, or average of selected meshes.
  const pivot = gizmoPivotWorld();
  gizmoGroup.position.copy(pivot);
  if (mesh.parent) mesh.parent.getWorldQuaternion(gizmoGroup.quaternion);
  drawMirrorCenter();
}

/** Cube ids currently affected by move/rotate/scale (multi-select or the single primary). */
function selectedCubes() {
  const ids = state.selection.length ? state.selection : (state.selectedCubeId ? [state.selectedCubeId] : []);
  const out = [];
  ids.forEach((id) => {
    const found = typeof findCube === "function" ? findCube(id) : { cube: null, part: null };
    if (found.cube) out.push({ cube: found.cube, part: found.part });
  });
  return out;
}

/** JSON-space rotate/scale pivot. "center" mode = geometric center of selected; "last" = primary cube center. */
function selectedPivotJson() {
  const cubes = selectedCubes();
  if (!cubes.length) return [0, 0, 0];
  if (state.pivotMode !== "center" && state.selectedCubeId) {
    const found = findCube(state.selectedCubeId);
    // The rotate math compares against root-frame centers — convert, or a single
    // selected block would rotate about a bogus pivot in its part-local frame.
    if (found.cube && found.part && typeof localToRoot === "function") return localToRoot(found.part, cubeCenter(found.cube));
    if (found.cube) return cubeCenter(found.cube);
  }
  let sx = 0, sy = 0, sz = 0;
  cubes.forEach(({ cube, part }) => {
    const c = cubeCenter(cube);
    // Convert to the ROOT frame so blocks in different parts share one pivot space
    // (otherwise the "geometric center" jumps between part-local frames).
    const rc = (typeof localToRoot === "function" && part) ? localToRoot(part, c) : c;
    sx += rc[0]; sy += rc[1]; sz += rc[2];
  });
  const n = cubes.length;
  return [sx / n, sy / n, sz / n];
}

/** World-space gizmo pivot: selected mesh position (single) or world average of all selected. */
function gizmoPivotWorld() {
  if (state.pivotMode !== "center") {
    if (state.selectedMesh) {
      const p = new THREE.Vector3();
      state.selectedMesh.getWorldPosition(p);
      return p;
    }
    return new THREE.Vector3();
  }
  const ids = state.selection.length ? state.selection : (state.selectedCubeId ? [state.selectedCubeId] : []);
  const v = new THREE.Vector3();
  let n = 0;
  ids.forEach((id) => {
    const m = meshByCubeId(id);
    if (!m) return;
    const p = new THREE.Vector3();
    m.getWorldPosition(p);
    v.add(p); n++;
  });
  return n ? v.divideScalar(n) : new THREE.Vector3();
}

function raycastGizmos() {
  if (!gizmoGroup || !gizmoGroup.visible) return [];
  raycaster.setFromCamera(pointer, camera);
  const prev = raycaster.params.Line ? raycaster.params.Line.threshold : 1;
  if (raycaster.params.Mesh) raycaster.params.Mesh.threshold = 0.15;
  const hits = raycaster.intersectObject(gizmoGroup, true).filter((h) => h.object.isMesh);
  if (raycaster.params.Line) raycaster.params.Line.threshold = prev;
  return hits;
}

function tryGizmoDown(e) {
  if (state.editMode || !state.selectedMesh) return false;
  const hits = raycastGizmos();
  if (!hits.length) return false;
  const obj = hits[0].object;
  const axis = obj.userData.gizmoAxis;
  if (!axis) return false;
  if (controls) controls.enabled = false;
  if (typeof pushModelUndo === "function") pushModelUndo();
  const { cube } = typeof selectedCube === "function" ? selectedCube() : { cube: null };
  // Drag the cube that was clicked — mirrors are independent (sync is opt-in via 应用对称).
  const src = cube;
  // Snapshot each selected block's start pose so rotate/scale accumulate incrementally.
  const startPose = {};
  selectedCubes().forEach(({ cube: c }) => {
    startPose[c.id] = { rot: cubeRot(c).slice(), center: cubeCenter(c).slice() };
  });
  gizmoDrag = {
    axis,
    mode: state.gizmoMode || "move",
    startX: e.clientX,
    startY: e.clientY,
    startCenter: src ? cubeCenter(src).slice() : [0, 0, 0],
    startRot: src ? cubeRot(src).slice() : [0, 0, 0],
    startSize: src ? cubeSize(src).slice() : [8, 8, 8],
    lastSize: src ? cubeSize(src).slice() : [8, 8, 8],
    pendingSize: null,
    cubeId: src ? src.id : state.selectedCubeId,
    startPose,
    jsonPivot: selectedPivotJson(),
    dirty: false,
    moved: false,
  };
  gizmoClickSuppress = true;
  return true;
}

/** Single click on an axis = step one increment; Alt = reverse. */
function stepGizmo(axis, reverse) {
  if (!gizmoDrag) return;
  const i = axis === "x" ? 0 : axis === "y" ? 1 : 2;
  const sign = reverse ? -1 : 1;
  if (gizmoDrag.mode === "move") {
    const step = (typeof num === "function" ? num("moveStep") : 1) || 1;
    const delta = [0, 0, 0];
    delta[i] = step * sign;
    selectedCubes().forEach(({ cube }) => translateCube(cube, delta[0], delta[1], delta[2]));
  } else if (gizmoDrag.mode === "rotate") {
    const stepDeg = (typeof num === "function" ? num("rotStep") : 15) || 15;
    const deg = stepDeg * sign * Math.PI / 180;
    const pivot = selectedPivotJson();
    selectedCubes().forEach(({ cube }) => {
      const r = cubeRot(cube);
      r[i] += deg;
      cube.rot = r;
    });
  } else if (gizmoDrag.mode === "scale") {
    const step = 1;
    const { cube } = typeof findCube === "function" ? findCube(gizmoDrag.cubeId) : { cube: null };
    if (!cube) return;
    const s = cubeSize(cube);
    s[i] = Math.max(0, s[i] + step * sign);
    if (typeof applySize === "function") {
      if (!applySize(s[0], s[1], s[2])) { setStatus("贴图空位不足，缩放被拒绝", false); return; }
    }
  }
}

function snapVal(v, step) {
  if (!step || step <= 0) return v;
  return Math.round(v / step) * step;
}

/** Mouse delta projected onto a gizmo axis (world units). Sign flipped to match on-screen drag. */
function dragAlongAxis(e, axis) {
  if (!gizmoGroup || !camera || !renderer) return 0;
  // JSON axis in preview-local space: the view flips Y, so JSON +Y is local -Y.
  const dir = new THREE.Vector3(axis === "x" ? 1 : 0, axis === "y" ? -1 : 0, axis === "z" ? 1 : 0);
  dir.applyQuaternion(gizmoGroup.quaternion);
  const origin = gizmoGroup.position.clone();
  const p0 = origin.clone().project(camera);
  const p1 = origin.clone().add(dir).project(camera);
  const el = renderer.domElement;
  const ax = (p1.x - p0.x) * el.clientWidth / 2;
  const ay = (p1.y - p0.y) * el.clientHeight / 2;
  const mx = e.clientX - gizmoDrag.startX;
  const my = -(e.clientY - gizmoDrag.startY);
  const len2 = ax * ax + ay * ay;
  if (len2 < 1e-6) return 0;
  // Positive = mouse moved along the JSON axis's on-screen direction.
  return (mx * ax + my * ay) / len2;
}

function onGizmoDrag(e) {
  if (!gizmoDrag) return;
  const i = gizmoDrag.axis === "x" ? 0 : gizmoDrag.axis === "y" ? 1 : 2;
  gizmoDrag.moved = true;
  const found = typeof findCube === "function" ? findCube(gizmoDrag.cubeId) : { cube: null, part: null };
  const src = found.cube;
  const host = found.part;
  if (!src) return;
  const world = dragAlongAxis(e, gizmoDrag.axis);
  if (gizmoDrag.mode === "move") {
    const step = (typeof num === "function" ? num("moveStep") : 1) || 1;
    const deltaPx = snapVal(world * 16, step);
    // The gizmo axis follows the block's rotation; translateCube is part-local.
    // Map the dragged axis into part-local JSON coords so the block follows the
    // arrow even when it (or its part chain) is rotated.
    let delta = [0, 0, 0];
    delta[i] = deltaPx;
    const pm = meshByCubeId(gizmoDrag.cubeId);
    if (pm && pm.parent) {
      const qp = new THREE.Quaternion();
      pm.parent.getWorldQuaternion(qp);
      const qg = new THREE.Quaternion();
      (pm.parent.parent || pm.parent).getWorldQuaternion(qg);
      qp.premultiply(qg.invert()); // pivot rotation relative to its part group
      const al = new THREE.Vector3(i === 0 ? 1 : 0, i === 1 ? -1 : 0, i === 2 ? 1 : 0).applyQuaternion(qp);
      delta = [al.x * deltaPx, -al.y * deltaPx, al.z * deltaPx];
    }
    const target = [
      gizmoDrag.startCenter[0] + delta[0],
      gizmoDrag.startCenter[1] + delta[1],
      gizmoDrag.startCenter[2] + delta[2],
    ];
    const cur = cubeCenter(src);
    const tv = [target[0] - cur[0], target[1] - cur[1], target[2] - cur[2]];
    // Move the block that was grabbed AND its symmetry group so a dragged mirror
    // also previews its source (and vice-versa).
    if (src.group) {
      groupCubes(src.group).forEach(({ cube: c }) => {
        let d = tv;
        if (c.mirrorOf && c.mirrorCombo && c.mirrorCombo.length && c.id !== src.id && !src.mirrorOf) {
          d = mirroredDeltaFor(c, tv, c.mirrorCombo);
        }
        translateCube(c, d[0], d[1], d[2]);
      });
    } else {
      selectedCubes().forEach(({ cube }) => translateCube(cube, tv[0], tv[1], tv[2]));
    }
    livePreviewGroup(src, host);
    gizmoDrag.dirty = true;
  } else if (gizmoDrag.mode === "rotate") {
    const stepDeg = (typeof num === "function" ? num("rotStep") : 15) || 15;
    // Signed screen X movement -> degrees; incremental from the drag start.
    const deg = snapVal((e.clientX - gizmoDrag.startX) / 3, stepDeg);
    // Live pivot: "center" mode rotates the group about its geometric center;
    // "last" rotates about the primary block. Recompute so multi-select changes apply.
    const pivot = state.pivotMode === "center" ? selectedPivotJson()
                : (gizmoDrag.jsonPivot || selectedPivotJson());
    const d2 = deg * Math.PI / 180;
    const cos = Math.cos(d2), sin = Math.sin(d2);
    selectedCubes().forEach(({ cube, part }) => {
      const base = (gizmoDrag.startPose && gizmoDrag.startPose[cube.id]) || { rot: cubeRot(cube).slice(), center: cubeCenter(cube).slice() };
      const rot = base.rot.slice();
      rot[i] += d2;
      cube.rot = rot;
      // rotate the block's start center about the pivot in the ROOT frame
      const rc = (typeof localToRoot === "function" && part) ? localToRoot(part, base.center) : base.center;
      let rx = rc[0] - pivot[0], ry = rc[1] - pivot[1], rz = rc[2] - pivot[2];
      let nrx = rx, nry = ry, nrz = rz;
      if (i === 2) {
        nrx = rx * cos - ry * sin; nry = rx * sin + ry * cos; nrz = rz;
      } else if (i === 1) {
        nrx = rx * cos + rz * sin; nry = ry; nrz = -rx * sin + rz * cos;
      } else {
        nry = ry * cos - rz * sin; nrz = ry * sin + rz * cos; nrx = rx;
      }
      const targetRoot = [pivot[0] + nrx, pivot[1] + nry, pivot[2] + nrz];
      const targetLocal = (typeof rootToLocal === "function" && part) ? rootToLocal(part, targetRoot) : targetRoot;
      const cur = cubeCenter(cube);
      translateCube(cube, targetLocal[0] - cur[0], targetLocal[1] - cur[1], targetLocal[2] - cur[2]);
    });
    livePreviewGroup(src, host);
    gizmoDrag.dirty = true;
  } else if (gizmoDrag.mode === "scale") {
    const s = gizmoDrag.startSize.slice();
    // Y axis screen projection is inverted; scale follows the arrow, so flip back.
    const w2 = gizmoDrag.axis === "y" ? -world : world;
    s[i] = Math.max(0, snapVal(gizmoDrag.startSize[i] + w2 * 16, 1));
    if (s[i] === gizmoDrag.lastSize[i]) return;
    gizmoDrag.lastSize = s.slice();
    gizmoDrag.pendingSize = s.slice();
    gizmoDrag.dirty = true;
    if (state.selectedMesh) {
      const sx = Math.max(0.05, s[0]) / Math.max(0.05, gizmoDrag.startSize[0]);
      const sy = Math.max(0.05, s[1]) / Math.max(0.05, gizmoDrag.startSize[1]);
      const sz = Math.max(0.05, s[2]) / Math.max(0.05, gizmoDrag.startSize[2]);
      state.selectedMesh.scale.set(sx, sy, sz);
    }
    livePreviewGroup(src, host);
  }
}

/** Mirror a JSON delta across a member's own mirror combo (so a dragged mirror also moves its source). */
function mirroredDeltaFor(mem, delta, combo) {
  const d = delta.slice();
  (combo || []).forEach((ax) => {
    const idx = ax === "x" ? 0 : ax === "y" ? 1 : 2;
    d[idx] = -d[idx];
  });
  return d;
}

function applyPivotPose(obj, cube) {
  if (!obj || !cube) return;
  const cx = (cube.min[0] + cube.max[0]) / 2;
  const cy = (cube.min[1] + cube.max[1]) / 2;
  const cz = (cube.min[2] + cube.max[2]) / 2;
  obj.position.set(cx / 16, -cy / 16, cz / 16);
  const rot = cube.rot || [0, 0, 0];
  obj.rotation.set(-(rot[0] || 0), rot[1] || 0, rot[2] || 0);
}

function livePreviewGroup(src, host) {
  // Live-update every selected block's mesh during drag.
  selectedCubes().forEach(({ cube }) => {
    const m = meshByCubeId(cube.id);
    if (m && m.parent) applyPivotPose(m.parent, cube);
  });
  // Also live-update the whole symmetry group so a dragged mirror previews its source.
  if (src.group && typeof groupCubes === "function") {
    groupCubes(src.group).forEach(({ cube: c }) => {
      const m = meshByCubeId(c.id);
      if (m && m.parent) applyPivotPose(m.parent, c);
    });
  }
  if (gizmoGroup && state.selectedMesh) {
    gizmoGroup.position.copy(gizmoPivotWorld());
    if (state.selectedMesh.parent) state.selectedMesh.parent.getWorldQuaternion(gizmoGroup.quaternion);
  }
  if (!modelGroup || !src.group) return;
  const origin = typeof originPointRoot === "function" ? originPointRoot() : [0, 0, 0];
  modelGroup.traverse((o) => {
    if (!o.isMesh || !o.userData.cube) return;
    const c = o.userData.cube;
    if (c.mirrorOf !== src.id) return;
    const combo = c.mirrorCombo || [];
    let minR = typeof localToRoot === "function" && host ? localToRoot(host, src.min.slice()) : src.min.slice();
    let maxR = typeof localToRoot === "function" && host ? localToRoot(host, src.max.slice()) : src.max.slice();
    const rot = (src.rot || [0, 0, 0]).slice();
    combo.forEach((ax) => {
      const ii = ax === "x" ? 0 : ax === "y" ? 1 : 2;
      const o = origin[ii];
      const a = minR[ii], b = maxR[ii];
      minR[ii] = Math.min(2 * o - a, 2 * o - b);
      maxR[ii] = Math.max(2 * o - a, 2 * o - b);
      if (ax === "x") { rot[1] = -rot[1]; rot[2] = -rot[2]; }
      if (ax === "y") { rot[0] = -rot[0]; rot[2] = -rot[2]; }
      if (ax === "z") { rot[0] = -rot[0]; rot[1] = -rot[1]; }
    });
    const minL = typeof rootToLocal === "function" && host ? rootToLocal(host, minR) : minR;
    const maxL = typeof rootToLocal === "function" && host ? rootToLocal(host, maxR) : maxR;
    applyPivotPose(o.parent, { min: minL, max: maxL, rot });
  });
}

function endGizmoDrag() {
  if (!gizmoDrag) return;
  const pending = gizmoDrag.pendingSize;
  const dirty = gizmoDrag.dirty;
  const cubeId = gizmoDrag.cubeId;
  gizmoDrag = null;
  if (controls) controls.enabled = true;
  if (pending && typeof applySize === "function") {
    if (!applySize(pending[0], pending[1], pending[2])) {
      setStatus("贴图空位不足，缩放被拒绝", false);
      rebuildModel();
      return;
    }
  } else if (dirty) {
    // Write the whole symmetry group back to JSON so mirrors persist (not just preview).
    const multi = (state.selection || []).length > 1;
    const found = typeof findCube === "function" ? findCube(cubeId) : { cube: null, part: null };
    if (!multi && found.cube && found.part && typeof syncMirrors === "function") {
      // keepCombo = the dragged block's own mirror combo, so the selection stays on it.
      syncMirrors(found.cube, found.part, found.cube.mirrorCombo || []);
    }
    rebuildModel();
  } else if (typeof fillCubePane === "function") fillCubePane();
}

/* ---------------------------------------------------------------- boot */
init().catch((e) => setStatus("初始化失败：" + e.message, false));
