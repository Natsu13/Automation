// Data model of the web editor: the same JSON the Android app stores and exports.
// Block = { id, type, params: { key: ParamValue }, groupId, disabled }
// ParamValue = {type:"rich", segments:[{type:"text",text}|{type:"var",ref}]} | {type:"choice",value}
//            | {type:"number",value} | {type:"bool",value}
// VarRef = {type:"input"|"clipboard"|"date"|"trigger"} | {type:"output",blockId} | {type:"event",key}
//        | {type:"variable",name} | {type:"secret",name}
(function () {
  "use strict";
  const C = window.CATALOG;
  const FORMAT = C.format;

  const blockDefs = new Map(C.blocks.map((b) => [b.type, b]));
  const eventDefs = new Map(C.events.map((e) => [e.key, e]));
  const eventValueDefs = new Map(C.eventValues.map((e) => [e.key, e]));
  const iconDefs = new Map(C.icons.map((i) => [i.key, i]));

  const uuid = () =>
    (crypto.randomUUID ? crypto.randomUUID() : "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
      const r = (Math.random() * 16) | 0;
      return (c === "x" ? r : (r & 0x3) | 0x8).toString(16);
    }));

  const clone = (v) => JSON.parse(JSON.stringify(v));

  const GROUP_STARTS = new Set(["IfStart", "WhileStart", "TryStart", "ParallelStart", "Scope"]);
  const kindOf = (type) => blockDefs.get(type)?.kind || "Normal";
  const isGroupStart = (type) => GROUP_STARTS.has(kindOf(type));

  function newBlock(type, groupId = null) {
    const def = blockDefs.get(type);
    const params = {};
    if (def) for (const p of def.params) params[p.key] = clone(p.default);
    return { id: uuid(), type, params, groupId, disabled: false };
  }

  /** The block plus the blocks that come with it (branches, end), as the app adds them. */
  function newBlockGroup(type) {
    const def = blockDefs.get(type);
    if (!def || !isGroupStart(type)) return [newBlock(type)];
    const group = uuid();
    return [newBlock(type, group), ...def.companions.map((t) => newBlock(t, group))];
  }

  /** Nesting of a flat block list, like engine/Structure.kt. */
  function analyze(blocks) {
    const depth = new Array(blocks.length).fill(0);
    const endOf = {};
    const startOf = {};
    const problems = {};
    const stack = [];
    blocks.forEach((block, i) => {
      const kind = kindOf(block.type);
      const top = stack[stack.length - 1];
      if (GROUP_STARTS.has(kind)) {
        depth[i] = stack.length;
        stack.push({ start: i, groupId: block.groupId, kind, hasElse: false, inner: [] });
      } else if (kind === "ElseIf" || kind === "Branch" || kind === "Else") {
        const ok = top && top.groupId === block.groupId && (
          kind === "ElseIf" ? top.kind === "IfStart" && !top.hasElse :
          kind === "Branch" ? top.kind === "ParallelStart" :
          top.kind !== "WhileStart" && top.kind !== "ParallelStart" && top.kind !== "Scope" && !top.hasElse);
        if (!ok) { problems[i] = true; depth[i] = stack.length; }
        else {
          depth[i] = stack.length - 1;
          if (kind === "Else") top.hasElse = true;
          top.inner.push(i);
        }
      } else if (kind === "End") {
        if (!top || top.groupId !== block.groupId) { problems[i] = true; depth[i] = stack.length; }
        else {
          stack.pop();
          depth[i] = stack.length;
          endOf[top.start] = i;
          for (const j of top.inner) endOf[j] = i;
          startOf[i] = top.start;
        }
      } else depth[i] = stack.length;
    });
    for (const open of stack) problems[open.start] = true;
    return { valid: Object.keys(problems).length === 0, depth, endOf, startOf, problems };
  }

  /** Indexes a drag / delete of block [i] works on: a group start takes its whole group. */
  function rangeOf(blocks, i, structure = analyze(blocks)) {
    if (isGroupStart(blocks[i].type) && structure.endOf[i] != null) return [i, structure.endOf[i]];
    return [i, i];
  }

  // ---------- automations ----------

  function newAutomation(name = "Nová automatizace") {
    return {
      name, icon: "bolt",
      triggerProcessText: false, triggerShare: false, triggerTile: false, triggerClipboard: false,
      conditions: [], conditionsAny: false, events: [], blocks: [],
    };
  }

  /** Fills missing fields so an automation from any source is complete. */
  function normalizeAutomation(a) {
    const base = newAutomation(a?.name || "Automatizace");
    const out = Object.assign(base, a || {});
    const fix = (b) => ({
      id: b.id || uuid(), type: String(b.type || "comment"), params: b.params || {},
      groupId: b.groupId ?? null, disabled: !!b.disabled,
    });
    out.blocks = (out.blocks || []).map(fix);
    out.conditions = (out.conditions || []).map(fix);
    out.events = (out.events || []).map((e) => ({ type: e.type, params: e.params || {}, id: e.id || uuid() }));
    return out;
  }

  /** Export file exactly as the app writes it (AutomationTransfer.encode). */
  function exportFile(automations) {
    return JSON.stringify({
      format: FORMAT, version: 1,
      automations: automations.map((a) => ({
        name: a.name, icon: a.icon,
        triggerProcessText: a.triggerProcessText, triggerShare: a.triggerShare,
        triggerTile: a.triggerTile, triggerClipboard: a.triggerClipboard,
        conditions: a.conditions, conditionsAny: a.conditionsAny,
        events: a.events, blocks: a.blocks,
      })),
    }, null, 4);
  }

  /** Accepts a full export file or a single exported automation, like the app. */
  function importText(text) {
    const trimmed = text.trim().replace(/^﻿/, "");
    let data;
    try { data = JSON.parse(trimmed); } catch (e) { throw new Error("parse"); }
    if (data && Array.isArray(data.automations)) {
      if (data.format && data.format !== FORMAT) throw new Error("format");
      return data.automations.map(normalizeAutomation);
    }
    if (Array.isArray(data)) {
      // A bare list of blocks (what the app stores in its database).
      if (data.every((b) => b && typeof b.type === "string" && "params" in b)) {
        return [normalizeAutomation({ name: "Import", blocks: data })];
      }
      return data.map(normalizeAutomation);
    }
    if (data && typeof data === "object" && (data.blocks || data.name)) return [normalizeAutomation(data)];
    throw new Error("format");
  }

  // ---------- params ----------

  /** Whether the parameter is hidden for the block's current values (rule recorded from the app). */
  function isParamHidden(def, param, params) {
    const rule = param.hidden;
    if (rule == null) return false;
    if (typeof rule === "boolean") return rule;
    const current = rule.keys.map((k, i) => {
      const v = params[k] ?? def.params.find((p) => p.key === k)?.default;
      const kind = rule.kinds?.[i];
      if (!v) return null;
      if (kind === "text") return v.segments?.length ? "*" : "";
      if (kind && kind.samples) {
        // The largest tried number not above the value stands for it.
        let pick = 0;
        kind.samples.forEach((s, n) => { if (s <= v.value + 1e-6) pick = n; });
        return String(pick);
      }
      return String(v.value);
    });
    return rule.hiddenFor.some((combo) => combo.every((x, i) => x === current[i]));
  }

  function richToPlain(value) {
    if (!value || value.type !== "rich") return "";
    return value.segments.map((s) => (s.type === "text" ? s.text : "")).join("");
  }

  /** All block outputs, variables and secrets referred to (for the variable picker). */
  function collectNames(automation) {
    const variables = new Set();
    const secrets = new Set();
    const walk = (blocks) => blocks.forEach((b) => {
      if (b.type === "set_variable" || b.type === "change_variable") {
        const name = richToPlain(b.params.name).trim();
        if (name) variables.add(name);
      }
      Object.values(b.params).forEach((v) => {
        if (v && v.type === "rich") v.segments.forEach((s) => {
          if (s.type !== "var") return;
          if (s.ref.type === "variable") variables.add(s.ref.name);
          if (s.ref.type === "secret") secrets.add(s.ref.name);
        });
      });
    });
    walk(automation.blocks);
    walk(automation.conditions);
    return { variables: [...variables], secrets: [...secrets] };
  }

  window.Model = {
    C, FORMAT, blockDefs, eventDefs, eventValueDefs, iconDefs, uuid, clone, kindOf, isGroupStart,
    newBlock, newBlockGroup, analyze, rangeOf, newAutomation, normalizeAutomation, exportFile, importText,
    isParamHidden, richToPlain, collectNames,
  };
})();
