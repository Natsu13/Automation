// The "Kód" view: an automation written in the language of the "Skript" block, exactly as the
// app writes it (port of ScriptWriter in ui/editor/TextView.kt), and read back into blocks.
//
//   found = extract({source: input, what: "url", which: "first"})
//   if found != ""
//       share(found, "Sdílet")
//   end
//
// Reading back: a line that is a block call / if / for / … becomes that block; anything else in the
// script language becomes a "Skript" block. Values the text does not show (hidden parameters, ids)
// are taken over from the blocks the text was written from.
(function () {
  "use strict";
  const M = window.Model;

  const RESERVED = new Set([
    "if", "pokud", "else", "jinak", "end", "konec", "while", "dokud", "for", "pro", "return", "vrat",
    "break", "ukonci", "continue", "pokracuj", "and", "or", "not", "nebo", "zaroven", "true", "false",
    "null", "nic", "ano", "input", "vstup", "clipboard", "trigger", "text", "date", "count", "list", "type",
    "script", "skript", "try", "zkus", "catch", "kdyz_selze", "parallel", "soubezne", "branch", "vetev",
    "lock", "postupne", "time_limit", "limit",
  ]);
  // Words of the groups of steps, as Script.kt has them.
  const GROUP = {
    script: ["script", "skript"], try: ["try", "zkus"], catch: ["catch", "kdyz_selze"], parallel: ["parallel", "soubezne"],
    parallel_branch: ["branch", "vetev"], lock: ["lock", "postupne"], time_limit: ["time_limit", "limit"],
  };
  const OPPOSITE = {
    equals: "not_equals", not_equals: "equals", contains: "not_contains", not_contains: "contains",
    empty: "not_empty", not_empty: "empty", greater: "less_eq", less_eq: "greater", less: "greater_eq", greater_eq: "less",
  };
  const FUNCTIONS = new Set([
    "len", "upper", "lower", "trim", "contains", "starts", "ends", "substr", "str", "num", "int",
    "round", "floor", "ceil", "abs", "sqrt", "min", "max", "sum", "avg", "calc", "range", "keys",
    "values", "push", "tojson", "iif", "now", "event", "secret", "block", "exact", "matches",
  ]);
  const SHADOWED = new Set(["replace", "text", "random", "date", "split", "join", "find", "match", "type", "json"]);
  /** Blocks the language has no word for: written as a comment with their title. */
  const ONLY_BLOCKS = ["try", "catch", "end_try", "parallel", "parallel_branch", "end_parallel", "lock", "end_lock", "time_limit", "end_time_limit"];
  const NOTE = { cs: "ve skriptu jen jako bloky", en: "only as blocks, not in a script" };
  const PARALLEL = { cs: "souběžně", en: "in parallel" };
  /** Words of "# @triggers" and the automation fields they switch on. */
  const TRIGGER_FIELDS = { process_text: "triggerProcessText", share: "triggerShare", tile: "triggerTile", clipboard: "triggerClipboard" };
  const CMP = { "==": "equals", "!=": "not_equals", ">": "greater", "<": "less", ">=": "greater_eq", "<=": "less_eq" };
  const CMP_TEXT = { equals: "==", not_equals: "!=", greater: ">", less: "<", greater_eq: ">=", less_eq: "<=" };

  const deepEqual = (a, b) => JSON.stringify(canon(a)) === JSON.stringify(canon(b));
  function canon(v) {
    if (Array.isArray(v)) return v.map(canon);
    if (v && typeof v === "object") { const o = {}; for (const k of Object.keys(v).sort()) o[k] = canon(v[k]); return o; }
    return v;
  }

  function formatNumber(value) {
    const d = Math.fround(value); // the app stores numbers as Float
    if (Number.isInteger(d) && Math.abs(d) < 1e15) return String(d);
    return String(d);
  }
  const quote = (t) => '"' + t.replace(/\\/g, "\\\\").replace(/"/g, '\\"').replace(/\n/g, "\\n").replace(/\t/g, "\\t") + '"';
  const literal = (t) => (/^-?\d+(\.\d+)?$/.test(t) ? t : quote(t));
  const isIdentifier = (name) => /^[\p{L}_][\p{L}\p{N}_]*$/u.test(name) && !RESERVED.has(name.toLowerCase());
  const identifier = (text) => {
    const s = text.toLowerCase().normalize("NFD").replace(/\p{Mn}+/gu, "").replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
    return /^[0-9]/.test(s) ? "_" + s : s;
  };
  const plain = (v) => (v && v.type === "rich" ? v.segments.map((s) => (s.type === "text" ? s.text : "")).join("") : null);
  const titleOf = (type, lang) => { const d = M.blockDefs.get(type); return d ? d.title[lang] ?? d.title.cs : type; };

  /** Output names of the steps, as the app gives them. */
  function outputNamesOf(blocks) {
    const taken = new Set(blocks.filter((b) => b.type === "set_variable").map((b) => plain(b.params.name)).filter((x) => x != null));
    RESERVED.forEach((r) => taken.add(r));
    const names = {};
    for (const b of blocks) {
      const def = M.blockDefs.get(b.type);
      if (!def?.output) continue;
      let base = identifier(def.output.en || def.output.cs) || "value";
      if (M.blockDefs.has(base) || FUNCTIONS.has(base)) base += "_result";
      let name = base, n = 2;
      while (taken.has(name)) name = base + n++;
      taken.add(name);
      names[b.id] = name;
    }
    return names;
  }

  function usedOutputs(blocks) {
    const used = new Set();
    for (const b of blocks) for (const v of Object.values(b.params)) {
      if (v && v.type === "rich") for (const s of v.segments) if (s.type === "var" && s.ref.type === "output") used.add(s.ref.blockId);
    }
    return used;
  }

  /** The name a step's output has in the script, when the script writes one. */
  function shownNames(blocks) {
    const names = outputNamesOf(blocks), used = usedOutputs(blocks), out = {};
    for (const b of blocks) {
      if (!names[b.id]) continue;
      if (used.has(b.id) || ["text", "math", "calculate", "for_each", "repeat"].includes(b.type)) out[b.id] = names[b.id];
    }
    return out;
  }

  // ---------- writing (ScriptWriter) ----------

  function write(blocks, lang = "cs") {
    const structure = M.analyze(blocks);
    const outputNames = outputNamesOf(blocks);
    const used = usedOutputs(blocks);
    const param = (block, def, key) => block.params[key] ?? def?.params.find((p) => p.key === key)?.default;
    const choice = (block, def, key) => { const v = param(block, def, key); return v && v.type === "choice" ? v.value : ""; };

    const reference = (ref) => {
      switch (ref.type) {
        case "input": return "input";
        case "clipboard": return "clipboard";
        case "date": return "date()";
        case "trigger": return "trigger";
        case "event": return "event(" + quote(ref.key) + ")";
        case "secret": return "secret(" + quote(ref.name) + ")";
        case "variable": return isIdentifier(ref.name) ? ref.name : "var(" + quote(ref.name) + ")";
        case "output": return outputNames[ref.blockId] || "nic";
        default: return "nic";
      }
    };
    const expr = (block, def, key) => {
      const value = param(block, def, key);
      if (!value) return '""';
      switch (value.type) {
        case "choice": return quote(value.value);
        case "number": return formatNumber(value.value);
        case "bool": return String(value.value);
        case "rich": {
          const parts = value.segments.filter((s) => s.type !== "text" || s.text).map((s) => (s.type === "text" ? literal(s.text) : reference(s.ref)));
          return parts.length ? parts.join(" & ") : '""';
        }
      }
      return '""';
    };
    const operand = (block, def, key) => { const v = expr(block, def, key); return v.includes(" & ") ? "(" + v + ")" : v; };
    const code = (value) => (value && value.type === "rich" ? value.segments.map((s) => (s.type === "text" ? s.text : reference(s.ref))).join("") : "");
    const plainOrExpr = (block, def, key) => {
      const rich = param(block, def, key);
      if (!rich || rich.type !== "rich") return ['""'];
      if (rich.segments.some((s) => s.type === "var")) return [expr(block, def, key)];
      const list = plain(rich).split(/\r?\n/).map((l) => l.trim()).filter(Boolean).map(literal);
      return list.length ? list : ['""'];
    };
    const isEmptyIf = (i) => blocks[i]?.type === "if" && !blocks[i].disabled && blocks[i + 1] &&
      M.kindOf(blocks[i + 1].type) === "Else" && blocks[i + 1].groupId === blocks[i].groupId;

    const condition = (block, def, negate = false) => {
      if (choice(block, def, "mode") === "expert") {
        let written = code(block.params.expression);
        if (!written.trim()) written = "true";
        return negate ? "not (" + written + ")" : written;
      }
      const left = expr(block, def, "left"), right = expr(block, def, "right");
      let op = choice(block, def, "op");
      if (negate) { if (!OPPOSITE[op]) return "not (" + condition(block, def) + ")"; op = OPPOSITE[op]; }
      switch (op) {
        case "contains": return `contains(${left}, ${right})`;
        case "not_contains": return `not contains(${left}, ${right})`;
        case "starts": return `starts(${left}, ${right})`;
        case "ends": return `ends(${left}, ${right})`;
        case "matches": return `matches(${left}, ${right})`;
        case "empty": return `${left} == ""`;
        case "not_empty": return `${left} != ""`;
        default: return `${left} ${CMP_TEXT[op] || "=="} ${right}`;
      }
    };
    const assign = (block, value) => {
      const name = outputNames[block.id];
      return name && (used.has(block.id) || ["text", "math", "calculate"].includes(block.type)) ? `${name} = ${value}` : value;
    };
    const parallelNote = (block) => (block.params.parallel?.value === true ? "  # " + PARALLEL[lang] : "");
    const call = (block, def) => {
      const values = {};
      for (const p of def.params) values[p.key] = block.params[p.key] ?? p.default;
      const skippable = (p) => {
        const v = values[p.key];
        if (!deepEqual(v, p.default)) return false;
        return p.kind === "toggle" || p.kind === "number" || (v.type === "rich" && v.segments.every((s) => s.type === "text" && !s.text));
      };
      const shown = def.params.filter((p) => !M.isParamHidden(def, p, values));
      while (shown.length && skippable(shown[shown.length - 1])) shown.pop();
      const args = shown.map((p) => [p.key, expr(block, def, p.key)]);
      const name = SHADOWED.has(def.type) ? "block(" + quote(def.type) + (args.length ? ", " : "") : def.type + "(";
      const positional = shown.every((p, i) => def.params[i] === p);
      return name + (positional ? args.map((a) => a[1]).join(", ") : "{" + args.map(([k, v]) => k + ": " + v).join(", ") + "}") + ")";
    };

    const statement = (block, def, index) => {
      const title = titleOf(block.type, lang);
      switch (block.type) {
        case "set_variable": {
          const name = plain(block.params.name) ?? "";
          const value = expr(block, def, "value");
          return isIdentifier(name) ? `${name} = ${value}` : `set_variable(${quote(name)}, ${value})`;
        }
        case "if": return "if " + condition(block, def, isEmptyIf(index));
        case "else_if": return "else if " + condition(block, def);
        case "else": return "else";
        case "while": return "while " + condition(block, def);
        case "end_if": case "end_while": case "end_switch": return "end";
        case "for_each": return `for ${outputNames[block.id]} in ${expr(block, def, "list")}` + parallelNote(block);
        case "repeat": return `for ${outputNames[block.id]} in range(${expr(block, def, "count")})` + parallelNote(block);
        case "loop_control": return choice(block, def, "action") === "continue" ? "continue" : "break";
        case "return": return "return " + expr(block, def, "text");
        case "comment": return "# " + (plain(block.params.text) ?? "").replace(/\n/g, "\n# ");
        // Its own group: a "return" inside ends only this step, as in the app.
        case "script": return assign(block, "script") + "\n" +
          code(block.params.code).split("\n").map((l) => (l.trim() ? "    " + l : "")).join("\n") + "\nend";
        case "try": {
          const attempts = param(block, def, "attempts")?.value ?? 1;
          const backoff = param(block, def, "backoff")?.value === true;
          const args = attempts <= 1 ? "" : "(" + expr(block, def, "attempts") + ", " + expr(block, def, "delay") + (backoff ? ", true" : "") + ")";
          return assign(block, "try" + args);
        }
        case "catch": return "catch";
        case "parallel": return "parallel";
        case "parallel_branch": return "branch";
        case "lock": { const n = (plain(block.params.name) ?? "").trim(); return n ? `lock(${quote(n)})` : "lock"; }
        case "time_limit": return `time_limit(${expr(block, def, "seconds")})`;
        case "end_try": case "end_parallel": case "end_lock": case "end_time_limit": return "end";
        case "text": return assign(block, expr(block, def, "text"));
        case "math": return assign(block, choice(block, def, "mode") === "expert"
          ? `calc(${expr(block, def, "expression")})`
          : `${operand(block, def, "a")} ${choice(block, def, "op")} ${operand(block, def, "b")}`);
        case "calculate": return assign(block, `calc(${expr(block, def, "expression")})`);
        case "switch": return "# " + title + " " + expr(block, def, "value");
        case "case": {
          const start = blocks.find((b) => b.groupId === block.groupId);
          const value = start ? expr(start, M.blockDefs.get(start.type), "value") : "?";
          const first = blocks.find((b) => b.groupId === block.groupId && b.type === "case")?.id === block.id;
          return (first ? "if " : "else if ") + plainOrExpr(block, def, "value").map((o) => `${value} == ${o}`).join(" or ");
        }
      }
      if (ONLY_BLOCKS.includes(block.type)) return "# " + title + "  (" + NOTE[lang] + ")";
      return def.kind === "Normal" ? assign(block, call(block, def)) : "# " + title;
    };

    const lines = [];
    blocks.forEach((block, index) => {
      if (index > 0 && isEmptyIf(index - 1)) return;
      const def = M.blockDefs.get(block.type);
      const indent = "    ".repeat(structure.depth[index] || 0);
      const text = def ? statement(block, def, index) : "# ? " + block.type;
      // An empty line of a script gets no trailing spaces.
      for (const line of text.split("\n")) lines.push(!line && !block.disabled ? "" : indent + (block.disabled && !line.startsWith("#") ? "# " : "") + line);
    });
    return lines.join("\n");
  }

  // ---------- reading ----------

  class ScriptError extends Error { constructor(line, message) { super(message); this.line = line; } }

  function lex(text, line) {
    const tokens = [];
    let i = 0, comment = null;
    while (i < text.length) {
      const c = text[i];
      if (c === " " || c === "\t" || c === "\r") { i++; continue; }
      if (c === "#") { comment = text.slice(i + 1).trim(); break; }
      if (c === '"' || c === "'") {
        let j = i + 1, out = "";
        while (j < text.length && text[j] !== c) {
          if (text[j] === "\\") {
            const n = text[j + 1];
            out += n === "n" ? "\n" : n === "t" ? "\t" : n === "r" ? "\r" : n ?? "";
            j += 2;
          } else out += text[j++];
        }
        if (j >= text.length) throw new ScriptError(line, "unclosed_string");
        tokens.push({ t: "str", v: out, s: i, e: j + 1 });
        i = j + 1;
        continue;
      }
      const num = /^\d+(\.\d+)?/.exec(text.slice(i));
      if (num) { tokens.push({ t: "num", v: num[0], s: i, e: i + num[0].length }); i += num[0].length; continue; }
      const id = /^[\p{L}_][\p{L}\p{N}_]*/u.exec(text.slice(i));
      if (id) { tokens.push({ t: "id", v: id[0], s: i, e: i + id[0].length }); i += id[0].length; continue; }
      const op = /^(==|!=|>=|<=|[-+*/%^&(){}\[\],:=<>.!])/.exec(text.slice(i));
      if (op) { tokens.push({ t: "op", v: op[0], s: i, e: i + op[0].length }); i += op[0].length; continue; }
      throw new ScriptError(line, "unexpected:" + c);
    }
    return { tokens, comment };
  }

  /** Splits tokens at top-level occurrences of [pred]. */
  function splitTop(tokens, pred) {
    const parts = [[]];
    let depth = 0;
    for (const tk of tokens) {
      if (tk.t === "op" && "([{".includes(tk.v)) depth++;
      if (tk.t === "op" && ")]}".includes(tk.v)) depth--;
      if (depth === 0 && pred(tk)) { parts.push([]); continue; }
      parts[parts.length - 1].push(tk);
    }
    return parts;
  }
  /** Whether tokens[0] is "(" and its partner is the last token. */
  function wrapped(tokens, open = "(", close = ")") {
    if (!tokens.length || tokens[0].v !== open || tokens[tokens.length - 1].v !== close) return false;
    let depth = 0;
    for (let i = 0; i < tokens.length; i++) {
      if (tokens[i].t === "op" && "([{".includes(tokens[i].v)) depth++;
      if (tokens[i].t === "op" && ")]}".includes(tokens[i].v)) depth--;
      if (depth === 0 && i < tokens.length - 1) return false;
    }
    return true;
  }

  function parse(text, hints = {}) {
    const oldBlocks = hints.blocks || [];
    const oldNames = hints.blocks ? outputNamesOf(oldBlocks) : {};
    const oldByName = {};
    for (const [id, name] of Object.entries(oldNames)) oldByName[name] = oldBlocks.find((b) => b.id === id);
    const usedOld = new Set();

    const meta = {};                // settings from "# @…" lines: name, icon, triggers, events, conditions
    const out = [];                 // {block, explicit: {key: value}, name}
    const names = {};               // output name -> new block
    const variables = new Set();    // variables set so far (by steps or by script code)
    const defaultNames = {};        // how many steps got each default output name so far
    const stack = [];               // open groups
    let script = null;              // the Skript block being collected
    let body = null;                // the body of `script … end` being read
    let comment = null;             // the Poznámka block being collected

    // ---- values ----
    const refOf = (tokens) => {
      if (tokens.length === 1 && tokens[0].t === "id") {
        const v = tokens[0].v;
        if (v === "input" || v === "vstup") return { type: "input" };
        if (v === "clipboard") return { type: "clipboard" };
        if (v === "trigger") return { type: "trigger" };
        if (names[v]) return { type: "output", blockId: names[v].block.id };
        if (RESERVED.has(v.toLowerCase())) return null;
        return { type: "variable", name: v };
      }
      if (tokens.length >= 3 && tokens[0].t === "id" && tokens[1].v === "(" && tokens[tokens.length - 1].v === ")") {
        const fn = tokens[0].v, inner = tokens.slice(2, -1);
        if (fn === "date" && !inner.length) return { type: "date" };
        if (inner.length === 1 && inner[0].t === "str") {
          if (fn === "event") return { type: "event", key: inner[0].v };
          if (fn === "secret") return { type: "secret", name: inner[0].v };
          if (fn === "var") return { type: "variable", name: inner[0].v };
        }
      }
      return null;
    };
    /** "a" & name & 3 -> segments, or null when it is not a plain chain of values. */
    const richOf = (tokens) => {
      if (!tokens.length) return null;
      // Pieces stay separate, as the app keeps them ("\n" & "aplikace=" is two pieces).
      const segments = [];
      const add = (s) => segments.push(s);
      for (let part of splitTop(tokens, (tk) => tk.t === "op" && tk.v === "&")) {
        while (wrapped(part)) part = part.slice(1, -1);
        if (part.length === 1 && (part[0].t === "str" || part[0].t === "num")) { if (part[0].v !== "") add({ type: "text", text: part[0].v }); continue; }
        if (part.length === 2 && part[0].v === "-" && part[1].t === "num") { add({ type: "text", text: "-" + part[1].v }); continue; }
        // Only an & that really splits it (one inside f(a & b) does not, and would loop forever).
        if (part.length > 1 && splitTop(part, (tk) => tk.t === "op" && tk.v === "&").length > 1) { const r = richOf(part); if (!r) return null; r.segments.forEach(add); continue; }
        const ref = refOf(part);
        if (!ref) return null;
        add({ type: "var", ref });
      }
      return { type: "rich", segments };
    };
    /** Code (a script, an expression) with the names of outputs and built-in values as chips. */
    const codeOf = (src) => {
      const segments = [];
      const add = (s) => {
        const last = segments[segments.length - 1];
        if (s.type === "text" && last?.type === "text") last.text += s.text; else if (s.type !== "text" || s.text) segments.push(s);
      };
      const re = /("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b(?:date)\(\s*\)|\b(?:event|secret)\(\s*"(?:\\.|[^"\\])*"\s*\)|[\p{L}_][\p{L}\p{N}_]*)/gu;
      let last = 0, m;
      while ((m = re.exec(src))) {
        const tk = m[0];
        if (tk[0] === '"' || tk[0] === "'") continue;
        const prev = src[m.index - 1];
        if (prev === ".") continue;
        let ref = null;
        if (/^date\(/.test(tk)) ref = { type: "date" };
        else if (/^(event|secret)\(/.test(tk)) {
          const arg = JSON.parse(tk.slice(tk.indexOf('"'), tk.lastIndexOf('"') + 1));
          ref = tk.startsWith("event") ? { type: "event", key: arg } : { type: "secret", name: arg };
        } else if (tk === "input" || tk === "vstup") ref = { type: "input" };
        else if (tk === "clipboard") ref = { type: "clipboard" };
        else if (tk === "trigger") ref = { type: "trigger" };
        else if (names[tk] && src[m.index + tk.length] !== "(") ref = { type: "output", blockId: names[tk].block.id };
        if (!ref) continue;
        add({ type: "text", text: src.slice(last, m.index) });
        add({ type: "var", ref });
        last = m.index + tk.length;
      }
      add({ type: "text", text: src.slice(last) });
      return { type: "rich", segments };
    };
    const valueFor = (p, tokens) => {
      if (!p) return null;
      if (p.kind === "text") return richOf(tokens);
      if (p.kind === "choice" || p.kind === "automation") {
        if (tokens.length === 1 && (tokens[0].t === "str" || tokens[0].t === "num")) return { type: "choice", value: tokens[0].v };
        return null;
      }
      if (p.kind === "number") {
        const neg = tokens.length === 2 && tokens[0].v === "-";
        const n = neg ? tokens[1] : tokens[0];
        if ((tokens.length === 1 || neg) && n.t === "num") return { type: "number", value: (neg ? -1 : 1) * parseFloat(n.v) };
        return null;
      }
      if (p.kind === "toggle") {
        if (tokens.length === 1 && (tokens[0].v === "true" || tokens[0].v === "false")) return { type: "bool", value: tokens[0].v === "true" };
        return null;
      }
      return null;
    };

    // ---- conditions ----
    const conditionOf = (tokens, src) => {
      const simple = (tks) => {
        if (tks[0]?.v === "not" && tks[1]?.t === "id" && ["contains"].includes(tks[1].v)) {
          const r = simple(tks.slice(1)); return r && r.op === "contains" ? { ...r, op: "not_contains" } : null;
        }
        if (tks[0]?.v === "not" && wrapped(tks.slice(1))) {
          const r = simple(tks.slice(2, -1)); return r && OPPOSITE[r.op] ? { ...r, op: OPPOSITE[r.op] } : null;
        }
        if (tks[0]?.t === "id" && ["contains", "starts", "ends", "matches"].includes(tks[0].v) && wrapped(tks.slice(1))) {
          const args = splitTop(tks.slice(2, -1), (tk) => tk.v === ",");
          if (args.length !== 2) return null;
          const left = richOf(args[0]), right = richOf(args[1]);
          return left && right ? { op: tks[0].v, left, right } : null;
        }
        for (const [sym, op] of Object.entries(CMP)) {
          const parts = splitTop(tks, (tk) => tk.t === "op" && tk.v === sym);
          if (parts.length !== 2) continue;
          const left = richOf(parts[0]);
          if (!left) return null;
          if (parts[1].length === 1 && parts[1][0].t === "str" && parts[1][0].v === "" && (op === "equals" || op === "not_equals")) {
            return { op: op === "equals" ? "empty" : "not_empty", left, right: { type: "rich", segments: [] } };
          }
          const right = richOf(parts[1]);
          return right ? { op, left, right } : null;
        }
        return null;
      };
      const s = simple(tokens);
      if (s) return { mode: { type: "choice", value: "simple" }, left: s.left, op: { type: "choice", value: s.op }, right: s.right };
      return { mode: { type: "choice", value: "expert" }, expression: codeOf(src.trim()) };
    };

    // ---- blocks ----
    const def = (type) => M.blockDefs.get(type);
    const take = (type, name, forced) => {
      if (forced) { usedOld.add(forced.id); return forced; }
      // The old block this one stands for: by its output name, else the next unused one of its type.
      let old = name && oldByName[name];
      if (!old || old.type !== type || usedOld.has(old.id)) {
        const nth = out.filter((o) => o.block.type === type).length;
        old = oldBlocks.filter((b) => b.type === type)[nth];
        if (old && usedOld.has(old.id)) old = null;
      }
      if (old) usedOld.add(old.id);
      return old;
    };
    const finish = () => { script = null; comment = null; };
    const emit = (type, explicit = {}, opts = {}) => {
      const d = def(type);
      const old = take(type, opts.name, opts.old);
      const block = d ? M.newBlock(type) : { id: M.uuid(), type, params: {}, groupId: null, disabled: false };
      if (old) block.id = old.id;
      const params = {};
      // `x == ""` reads as "is empty", unless the block was "is equal to" an empty value.
      const sameAs = { empty: "equals", not_empty: "not_equals" };
      if (explicit.op && old?.params.op && sameAs[explicit.op.value] === old.params.op.value &&
        !(old.params.right?.segments || []).some((x) => x.type === "var" || x.text)) explicit = { ...explicit, op: M.clone(old.params.op) };
      // A step whose output the text does not name (Zkusit's error) keeps its old name.
      if (!opts.name && old && oldNames[old.id]) names[oldNames[old.id]] = { block };
      if (d && opts.keepAll && old) {
        // The text shows none of its settings (Zkusit, Skript kept whole): they stay as they were.
        Object.assign(params, M.clone(old.params), explicit);
      } else if (d) {
        for (const p of d.params) params[p.key] = explicit[p.key] ?? (old?.params[p.key] && M.isParamHidden(d, p, { ...old.params, ...explicit }) ? M.clone(old.params[p.key]) : M.clone(p.default));
        // Settings the script line does not show (a parallel loop's limits, …) stay as they were.
        for (const key of opts.keep || []) if (old?.params[key] && explicit[key] === undefined) params[key] = M.clone(old.params[key]);
      } else if (old) Object.assign(params, M.clone(old.params));
      block.params = params;
      block.disabled = opts.disabled ?? (opts.keepDisabled ? !!old?.disabled : false);
      // groups
      const kind = M.kindOf(type);
      if (M.isGroupStart(type)) {
        block.groupId = old?.groupId || M.uuid();
        stack.push({ block, type, indent: opts.indent ?? 0 });
      } else if (kind === "Else" || kind === "ElseIf" || kind === "Branch") {
        block.groupId = stack[stack.length - 1]?.block.groupId || M.uuid();
      } else if (kind === "End") {
        block.groupId = stack.pop()?.block.groupId || M.uuid();
      } else block.groupId = null;
      out.push({ block, name: opts.name });
      // The name the output goes by: the one written, or the one "Show as script" gives it
      // (a Skript block's "script_result", Zkusit's "error"), as the app's ScriptReader does.
      if (d?.output) {
        let base = identifier(d.output.en || d.output.cs) || "value";
        if (M.blockDefs.has(base) || FUNCTIONS.has(base)) base += "_result";
        const n = (defaultNames[base] || 0) + 1;
        defaultNames[base] = n;
        const fallback = n === 1 ? base : base + n;
        if (!opts.name && !names[fallback] && !variables.has(fallback)) names[fallback] = { block };
      }
      if (opts.name) names[opts.name] = { block };
      if (type === "set_variable") variables.add((plain(params.name) || "").trim());
      return block;
    };

    /** Reads one statement; returns false when it is not one the blocks can express. */
    const statement = (tokens, src, info) => {
      const { indent, disabled, note } = info;
      if (!tokens.length) return false;
      const w = tokens[0].t === "id" ? tokens[0].v : null;
      const top = stack[stack.length - 1];
      const inSwitch = top && top.type === "switch" && indent === top.indent;

      if (w === "if" || (w === "else" && tokens[1]?.v === "if")) {
        const condTokens = tokens.slice(w === "if" ? 1 : 2);
        const condSrc = src.slice(condTokens[0]?.s ?? src.length);
        if (inSwitch) {
          // "if value == "a" or value == "b"" is a branch of "Vyber podle".
          const opts = splitTop(condTokens, (tk) => tk.t === "id" && tk.v === "or").map((part) => splitTop(part, (tk) => tk.v === "=="));
          if (opts.every((p) => p.length === 2)) {
            const rights = opts.map((p) => p[1]);
            let value;
            if (rights.every((r) => r.length === 1 && (r[0].t === "str" || r[0].t === "num"))) {
              value = { type: "rich", segments: [{ type: "text", text: rights.map((r) => r[0].v).join("\n") }] };
            } else value = richOf(rights[0]);
            if (value) { emit("case", { value }, { disabled }); return true; }
          }
        }
        if (!condTokens.length) return false;
        emit(w === "if" ? "if" : "else_if", conditionOf(condTokens, condSrc), { disabled: w === "if" ? disabled : false, indent });
        return true;
      }
      if (w === "else" && tokens.length === 1) { if (disabled) return false; emit("else"); return true; }
      if (w === "while" && tokens.length > 1) {
        emit("while", conditionOf(tokens.slice(1), src.slice(tokens[1].s)), { disabled, indent });
        return true;
      }
      if (w === "end" && tokens.length === 1) {
        if (disabled || !top) return false;
        const endType = { if: "end_if", switch: "end_switch", while: "end_while", for_each: "end_while", repeat: "end_while",
          try: "end_try", parallel: "end_parallel", lock: "end_lock", time_limit: "end_time_limit" }[top.type];
        if (!endType) return false;
        emit(endType);
        return true;
      }
      const grouped = group(tokens, indent, disabled);
      if (grouped !== null) return grouped;
      if (w === "for" && tokens[1]?.t === "id" && tokens[2]?.v === "in") {
        const name = tokens[1].v, rest = tokens.slice(3);
        const parallel = note && (note === PARALLEL.cs || note === PARALLEL.en) ? { type: "bool", value: true } : { type: "bool", value: false };
        if (rest[0]?.v === "range" && wrapped(rest.slice(1))) {
          const count = richOf(rest.slice(2, -1));
          if (count) { emit("repeat", { count, parallel }, { name, disabled, indent, keep: ["limit", "gap", "collect"] }); return true; }
        }
        const list = richOf(rest);
        if (list) { emit("for_each", { list, parallel }, { name, disabled, indent, keep: ["limit", "gap", "collect"] }); return true; }
        return false;
      }
      if ((w === "continue" || w === "break") && tokens.length === 1) { emit("loop_control", { action: { type: "choice", value: w } }, { disabled }); return true; }
      if (w === "return") {
        const text = tokens.length > 1 ? richOf(tokens.slice(1)) : { type: "rich", segments: [] };
        if (!text) return false;
        emit("return", { text }, { disabled });
        return true;
      }

      // name = …
      let name = null, value = tokens;
      if (tokens[0].t === "id" && tokens[1]?.v === "=" && tokens.length > 2) { name = tokens[0].v; value = tokens.slice(2); }
      const blockCall = callOf(value);
      if (blockCall) {
        const d = def(blockCall.type);
        if (d.kind !== "Normal" || (name && !d.output)) return false;
        emit(blockCall.type, blockCall.params, { name: name || undefined, disabled });
        return true;
      }
      if (!name && w === "set_variable" && wrapped(tokens.slice(1))) {
        const args = splitTop(tokens.slice(2, -1), (tk) => tk.v === ",");
        if (args.length === 2 && args[0].length === 1 && args[0][0].t === "str") {
          const v = richOf(args[1]);
          if (v) { emit("set_variable", { name: { type: "rich", segments: [{ type: "text", text: args[0][0].v }] }, value: v }, { disabled }); return true; }
        }
        return false;
      }
      if (!name) return false;
      const was = oldByName[name]?.type;
      // "total = total * 0.9" changes an existing variable: only script code does exactly that.
      if (!was && (names[name] || variables.has(name)) && !richOf(value)) return false;
      // name = calc(…)
      if (value[0]?.v === "calc" && wrapped(value.slice(1))) {
        const exprSrc = src.slice(value[2]?.s ?? value[1].e, value[value.length - 1].s);
        const inner = richOf(value.slice(2, -1)) || codeOf(exprSrc);
        if (was === "calculate") emit("calculate", { expression: inner }, { name, disabled });
        else emit("math", { mode: { type: "choice", value: "expert" }, expression: inner }, { name, disabled });
        return true;
      }
      // name = a + b
      for (const op of ["+", "-", "*", "/", "%"]) {
        const parts = splitTop(value, (tk) => tk.t === "op" && tk.v === op);
        if (parts.length !== 2 || !parts[0].length) continue;
        const a = richOf(parts[0]), b = richOf(parts[1]);
        if (a && b) { emit("math", { mode: { type: "choice", value: "simple" }, a, op: { type: "choice", value: op }, b }, { name, disabled }); return true; }
      }
      const rich = richOf(value);
      if (!rich) return false;
      if (was === "text") emit("text", { text: rich }, { name, disabled });
      else emit("set_variable", { name: { type: "rich", segments: [{ type: "text", text: name }] }, value: rich }, { disabled });
      return true;
    };

    /** block_type(…) or block("type", …) with its parameters. */
    const callOf = (tokens) => {
      if (tokens.length < 3 || tokens[0].t !== "id" || !wrapped(tokens.slice(1))) return null;
      let type = tokens[0].v, inner = tokens.slice(2, -1);
      if (type === "block") {
        if (inner[0]?.t !== "str") return null;
        type = inner[0].v;
        inner = inner.slice(1);
        if (inner.length) { if (inner[0].v !== ",") return null; inner = inner.slice(1); }
      } else if (SHADOWED.has(type)) return null;
      const d = def(type);
      if (!d) return null;
      const params = {};
      if (wrapped(inner, "{", "}")) {
        for (const pair of splitTop(inner.slice(1, -1), (tk) => tk.v === ",")) {
          if (pair.length < 3 || pair[0].t !== "id" || pair[1].v !== ":") return null;
          const p = d.params.find((x) => x.key === pair[0].v);
          const v = valueFor(p, pair.slice(2));
          if (!v) return null;
          params[p.key] = v;
        }
      } else if (inner.length) {
        const args = splitTop(inner, (tk) => tk.v === ",");
        if (args.length > d.params.length) return null;
        for (let i = 0; i < args.length; i++) {
          const v = valueFor(d.params[i], args[i]);
          if (!v) return null;
          params[d.params[i].key] = v;
        }
      }
      return { type, params };
    };

    /**
     * "# Zkusit  (ve skriptu jen jako bloky)". Exact titles first; then, since people and AI models
     * write "# On error (only as blocks)" or "# End try (…)", by key words in either language.
     */
    const markerTitle = (body) => {
      const m = /^(.*?)\s*\(([^()]*)\)\s*$/.exec(body);
      if (!m) return null;
      const note = fold(m[2]);
      if (m[2] !== NOTE.cs && m[2] !== NOTE.en && !/blok|block/.test(note)) return null;
      const exact = ONLY_BLOCKS.find((t) => [def(t)?.title.cs, def(t)?.title.en].includes(m[1].trim()));
      if (exact) return exact;
      const t = fold(m[1]);
      const end = /\b(end|konec|ukonc)/.test(t);
      if (/(time ?limit|casov|timeout)/.test(t)) return end ? "end_time_limit" : "time_limit";
      if (/(lock|one at a time|jeden po|zamek)/.test(t)) return end ? "end_lock" : "lock";
      if (/(branch|vetev|zaroven|at the same time)/.test(t) && !end) return "parallel_branch";
      if (/(parallel|soubez)/.test(t)) return end ? "end_parallel" : "parallel";
      if (/(catch|fail|error|except|selz|chyb)/.test(t) && !end) return "catch";
      if (/(try|zkus)/.test(t)) return end ? "end_try" : "try";
      return null;
    };
    const fold = (s) => s.toLowerCase().normalize("NFD").replace(/\p{Mn}+/gu, "").trim();

    // A Skript block is written as its code; an unchanged one is recognised and kept whole.
    const refName = (ref) => (ref.type === "output" ? oldNames[ref.blockId] || "nic" : ref.type === "date" ? "date()"
      : ref.type === "event" ? `event(${quote(ref.key)})` : ref.type === "secret" ? `secret(${quote(ref.name)})`
      : ref.type === "variable" ? (isIdentifier(ref.name) ? ref.name : `var(${quote(ref.name)})`) : ref.type);
    const oldScripts = oldBlocks.filter((b) => b.type === "script" && !b.disabled && b.params.code?.type === "rich").map((b) => ({
      block: b, lines: b.params.code.segments.map((x) => (x.type === "text" ? x.text : refName(x.ref))).join("").split("\n"),
    }));
    const lines = text.replace(/\r/g, "").split("\n");
    for (let n = 0; n < lines.length; n++) {
      const pad = lines[n].match(/^[ \t]*/)[0];
      const known = !body && oldScripts.find((sc) => !usedOld.has(sc.block.id) && sc.lines.length <= lines.length - n &&
        (sc.lines.length > 1 || sc.lines[0].trim()) &&
        sc.lines.every((l, k) => (pad + l).trimEnd() === lines[n + k].trimEnd()));
      if (known) {
        finish();
        emit("script", {}, { keepAll: true, old: known.block, name: oldNames[known.block.id] });
        n += known.lines.length - 1;
        continue;
      }
      line(lines[n], n);
    }
    /** `[x =] script`, `[x =] try(3, 2, true)`, `catch`, `parallel`, `branch`, `lock("a")`, `time_limit(10)`; null = none. */
    function group(tokens, indent, disabled) {
      let target = null, rest = tokens;
      if (tokens.length >= 3 && tokens[0].t === "id" && tokens[1].v === "=") { target = tokens[0].v; rest = tokens.slice(2); }
      const word = rest[0]?.t === "id" ? rest[0].v.toLowerCase() : null;
      const type = word && Object.keys(GROUP).find((k) => GROUP[k].includes(word));
      if (!type) return null;
      let args = [];
      if (rest.length > 1) {
        if (!wrapped(rest.slice(1))) return null;
        const inner = rest.slice(2, -1);
        args = inner.length ? splitTop(inner, (tk) => tk.v === ",") : [];
      }
      if (target && type !== "script" && type !== "try") return false;
      const d = def(type);
      const values = (keys) => {
        if (args.length > keys.length) return null;
        const out = {};
        for (let i = 0; i < args.length; i++) {
          const v = valueFor(d.params.find((p) => p.key === keys[i]), args[i]);
          if (!v) return null;
          out[keys[i]] = v;
        }
        return out;
      };
      let params;
      if (["script", "catch", "parallel", "parallel_branch"].includes(type)) { if (args.length) return false; params = {}; }
      else if (type === "try") params = values(["attempts", "delay", "backoff"]);
      else if (type === "lock") params = values(["name"]);
      else { params = values(["seconds"]); if (params && !Object.keys(params).length) params = null; }
      if (!params) return false;
      if (disabled && (type === "catch" || type === "parallel_branch")) return false;
      const block = emit(type, params, { name: target || undefined, disabled, indent, keepAll: type === "script" });
      if (type === "script") body = { block, indent, disabled, lines: [] };
      return true;
    }

    /** A line inside `script … end`; false when the body has ended (the line is read as usual then). */
    function bodyLine(raw) {
      const lead = raw.length - raw.trimStart().length;
      let content = raw.trimStart();
      if (!content) { body.lines.push(""); return true; }
      if (body.disabled) {
        if (!content.startsWith("#")) { closeBody(); return false; }
        content = content.replace(/^#\s?/, "");
        if (lead === body.indent && content.trim() === "end") { closeBody(); return true; }
        body.lines.push(content.replace(/^ {4}/, ""));
        return true;
      }
      if (lead <= body.indent) {
        const ended = content === "end" && lead === body.indent;
        closeBody();
        return ended;
      }
      body.lines.push(raw.slice(Math.min(lead, body.indent + 4)));
      return true;
    }

    function closeBody() {
      body.block.params.code = codeOf(body.lines.join("\n").replace(/[\n ]+$/, ""));
      body = null;
    }

    function line(raw, n) {
      if (body && bodyLine(raw.replace(/\t/g, "    "))) return;
      const lineNo = n + 1;
      const indent = raw.match(/^[ \t]*/)[0].replace(/\t/g, "    ").length;
      const trimmed = raw.trim();
      if (!trimmed) { if (comment) comment = null; return; }

      // "# @name …", "# @event …": the automation's settings (see header()); comments for the Skript block.
      const directive = /^#\s*@(\w+)\s*(.*)$/.exec(trimmed);
      if (directive && readDirective(directive[1], directive[2].trim())) { finish(); return; }
      if (trimmed.startsWith("#")) {
        const body = trimmed.replace(/^#\s?/, "");
        const marker = markerTitle(body);
        if (marker) { finish(); emit(marker, {}, { keepDisabled: true, keepAll: true, indent }); return; }
        const sw = def("switch");
        for (const title of [sw.title.cs, sw.title.en]) {
          if (body.startsWith(title + " ")) {
            const lexed = (() => { try { return lex(body.slice(title.length + 1), lineNo).tokens; } catch (e) { return null; } })();
            const value = lexed && richOf(lexed);
            if (value) { finish(); emit("switch", { value }, { indent }); return; }
          }
        }
        if (body.startsWith("? ")) { finish(); emit(body.slice(2).trim(), {}, { keepDisabled: true, keepAll: true }); return; }
        // A switched-off step is written as a comment.
        let lexed = null;
        try { lexed = lex(body, lineNo); } catch (e) { lexed = null; }
        if (lexed && lexed.tokens.length) {
          const mark = out.length, savedStack = stack.slice(), savedUsed = new Set(usedOld), savedNames = { ...names };
          const saveScript = script, saveComment = comment;
          script = null; comment = null;
          if (statement(lexed.tokens, body, { indent, disabled: true, note: lexed.comment })) { finish(); return; }
          out.length = mark; stack.length = 0; stack.push(...savedStack); script = saveScript; comment = saveComment;
          usedOld.clear(); savedUsed.forEach((x) => usedOld.add(x));
          for (const k of Object.keys(names)) if (!(k in savedNames)) delete names[k];
        }
        script = null;
        if (comment) comment.params.text.segments[0].text += "\n" + body;
        else comment = emit("comment", { text: { type: "rich", segments: [{ type: "text", text: body }] } });
        return;
      }

      let lexed;
      try { lexed = lex(trimmed, lineNo); } catch (e) { lexed = null; }
      comment = null;
      if (lexed && statement(lexed.tokens, trimmed, { indent, disabled: false, note: lexed.comment })) { script = null; return; }
      // Anything else is script code; following lines of code go to the same Skript block.
      const assigned = /^([\p{L}_][\p{L}\p{N}_]*)\s*[-+*/&]?=(?!=)/u.exec(trimmed);
      if (assigned) variables.add(assigned[1]);
      const code = raw.slice(Math.min(indent, script ? script.indent : indent));
      if (script) {
        const text = plainCode(script.block) + "\n" + code;
        script.block.params.code = codeOf(text);
      } else {
        const block = emit("script", { code: codeOf(trimmed) });
        script = { block, indent };
      }
    }
    function plainCode(block) {
      // Back to text with names, so the whole code is converted to chips again.
      return block.params.code.segments.map((s) => (s.type === "text" ? s.text : s.ref.type === "output"
        ? Object.keys(names).find((k) => names[k].block.id === s.ref.blockId) || "nic"
        : s.ref.type === "date" ? "date()" : s.ref.type === "event" ? `event(${quote(s.ref.key)})`
        : s.ref.type === "secret" ? `secret(${quote(s.ref.name)})` : s.ref.type)).join("");
    }
    function readDirective(word, rest) {
      const str = (s) => { try { return JSON.parse(s); } catch (e) { return s.replace(/^"|"$/g, ""); } };
      switch (word) {
        case "name": meta.name = str(rest); return true;
        case "icon": meta.icon = str(rest); return true;
        case "triggers": {
          meta.triggers = {};
          for (const w of rest.split(/[,\s]+/).filter(Boolean)) {
            const field = TRIGGER_FIELDS[w];
            if (field) meta.triggers[field] = true;
          }
          return true;
        }
        case "event": {
          const m = /^([a-z_]+)\s*(?:\((.*)\))?\s*$/.exec(rest);
          if (!m || !M.eventDefs.has(m[1])) return false;
          const params = { ...M.eventDefs.get(m[1]).defaults };
          for (const [, k, v] of (m[2] || "").matchAll(/(\w+)\s*:\s*("(?:\\.|[^"\\])*"|[^,\s)]+)/g)) params[k] = String(str(v));
          (meta.events = meta.events || []).push({ type: m[1], params, id: M.uuid() });
          return true;
        }
        case "conditions": meta.conditionsAny = rest === "any"; return rest === "any" || rest === "all";
        case "condition": {
          // The same call as in the script: launch_condition({source: "wifi_name", op: "equals", right: "Domov"})
          const call = /^launch_condition\b/.test(rest) ? rest : "launch_condition(" + rest + ")";
          const lexed = (() => { try { return lex(call, 0).tokens; } catch (e) { return null; } })();
          const parsed = lexed && callOf(lexed);
          if (!parsed || parsed.type !== "launch_condition") return false;
          const block = M.newBlock("launch_condition");
          Object.assign(block.params, parsed.params);
          (meta.conditions = meta.conditions || []).push(block);
          return true;
        }
      }
      return false;
    }
    if (body) closeBody();
    return { blocks: out.map((o) => o.block), meta };
  }

  // ---------- highlighting ----------
  const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  const KEYWORDS = new Set(["if", "else", "end", "while", "for", "in", "return", "break", "continue", "and", "or", "not",
    "script", "try", "catch", "parallel", "branch", "lock", "time_limit"]);
  function highlight(text) {
    let out = "";
    const re = /("(?:\\.|[^"\\\n])*"?|'(?:\\.|[^'\\\n])*'?|#[^\n]*|\b\d+(?:\.\d+)?\b|[\p{L}_][\p{L}\p{N}_]*|[^"'#\p{L}_\d]+|.)/gu;
    const tokens = text.match(re) || [];
    for (let i = 0; i < tokens.length; i++) {
      const t = tokens[i];
      const next = tokens.slice(i + 1, i + 3).join("");
      if (t[0] === '"' || t[0] === "'") out += '<span class="tk-str">' + esc(t) + "</span>";
      else if (t[0] === "#") out += '<span class="tk-com">' + esc(t) + "</span>";
      else if (/^\d/.test(t)) out += '<span class="tk-num">' + t + "</span>";
      else if (/^[\p{L}_]/u.test(t)) {
        if (KEYWORDS.has(t)) out += '<span class="tk-kw">' + t + "</span>";
        else if (t === "true" || t === "false") out += '<span class="tk-num">' + t + "</span>";
        else if (["input", "clipboard", "trigger"].includes(t)) out += '<span class="tk-ref">' + t + "</span>";
        else if (/^\s*\(/.test(next)) out += '<span class="' + (M.blockDefs.has(t) || t === "block" ? "tk-fn" : "tk-dir") + '">' + t + "</span>";
        else if (/^\s*:/.test(next)) out += '<span class="tk-key">' + t + "</span>";
        else if (/^\s*=(?!=)/.test(next)) out += '<span class="tk-label">' + t + "</span>";
        else out += '<span class="tk-word">' + t + "</span>";
      } else out += esc(t);
    }
    return out + "\n";
  }

  /**
   * The automation's settings as comment lines in front of the script, so one text describes a whole
   * automation (what an AI model writes, the "download as code" file). The Skript block ignores them.
   */
  function header(automation) {
    const lines = ["# @name " + JSON.stringify(automation.name || "")];
    if (automation.icon) lines.push("# @icon " + automation.icon);
    const triggers = Object.entries(TRIGGER_FIELDS).filter(([, f]) => automation[f]).map(([w]) => w);
    if (triggers.length) lines.push("# @triggers " + triggers.join(", "));
    for (const e of automation.events || []) {
      const args = Object.entries(e.params || {}).map(([k, v]) => k + ": " + JSON.stringify(String(v)));
      lines.push("# @event " + e.type + (args.length ? "(" + args.join(", ") + ")" : ""));
    }
    if ((automation.conditions || []).length) {
      lines.push("# @conditions " + (automation.conditionsAny ? "any" : "all"));
      for (const c of automation.conditions) lines.push("# @condition " + write([c]).replace(/^# /, ""));
    }
    return lines.join("\n");
  }

  window.Script = { write, parse, header, highlight, shownNames, outputNamesOf, ScriptError };
})();
