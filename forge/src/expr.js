// Tiny safe expression evaluator for parametric fields. No eval / Function.
// Supports + - * / % ^, parentheses, named parameters, constants and functions.
// Trig works in DEGREES (CAD convention).

export class ExprError extends Error {}

const D2R = Math.PI / 180;
const FN = {
  sin: (x) => Math.sin(x * D2R), cos: (x) => Math.cos(x * D2R), tan: (x) => Math.tan(x * D2R),
  asin: (x) => Math.asin(x) / D2R, acos: (x) => Math.acos(x) / D2R, atan: (x) => Math.atan(x) / D2R,
  atan2: (y, x) => Math.atan2(y, x) / D2R,
  sqrt: Math.sqrt, abs: Math.abs, min: Math.min, max: Math.max, round: Math.round,
  floor: Math.floor, ceil: Math.ceil, pow: Math.pow, hypot: Math.hypot,
};
const CONST = { pi: Math.PI, tau: Math.PI * 2 };

const astCache = new Map();

function tokenize(src) {
  const t = [];
  const re = /\s*(?:(\d+\.?\d*(?:e[+-]?\d+)?|\.\d+(?:e[+-]?\d+)?)|([A-Za-z_][A-Za-z_0-9]*)|(.))/gy;
  let m;
  while (re.lastIndex < src.length && (m = re.exec(src))) {
    if (m[1] !== undefined) t.push({ t: 'n', v: parseFloat(m[1]) });
    else if (m[2] !== undefined) t.push({ t: 'i', v: m[2] });
    else t.push({ t: 'o', v: m[3] });
  }
  return t;
}

function parse(src) {
  if (astCache.has(src)) return astCache.get(src);
  const toks = tokenize(src);
  let p = 0;
  const peek = () => toks[p];
  const eat = (v) => {
    const k = toks[p];
    if (!k || k.t !== 'o' || (v && k.v !== v)) throw new ExprError(`expected ${v || 'operator'} in "${src}"`);
    p++;
    return k;
  };
  const isOp = (v) => toks[p] && toks[p].t === 'o' && toks[p].v === v;
  function expr() {
    let l = term();
    while (isOp('+') || isOp('-')) { const o = toks[p++].v; l = { o, l, r: term() }; }
    return l;
  }
  function term() {
    let l = unary();
    while (isOp('*') || isOp('/') || isOp('%')) { const o = toks[p++].v; l = { o, l, r: unary() }; }
    return l;
  }
  function unary() {
    if (isOp('-')) { p++; return { o: 'neg', l: unary() }; }
    if (isOp('+')) { p++; return unary(); }
    return power();
  }
  function power() {
    const b = atom();
    if (isOp('^')) { p++; return { o: '^', l: b, r: unary() }; }
    return b;
  }
  function atom() {
    const k = peek();
    if (!k) throw new ExprError(`unexpected end of "${src}"`);
    if (k.t === 'n') { p++; return { n: k.v }; }
    if (k.t === 'i') {
      p++;
      if (isOp('(')) {
        p++;
        const args = [];
        if (!isOp(')')) { args.push(expr()); while (isOp(',')) { p++; args.push(expr()); } }
        eat(')');
        return { f: k.v, args };
      }
      return { id: k.v };
    }
    if (isOp('(')) { p++; const e = expr(); eat(')'); return e; }
    throw new ExprError(`unexpected "${k.v}" in "${src}"`);
  }
  if (!toks.length) throw new ExprError('empty expression');
  const ast = expr();
  if (p < toks.length) throw new ExprError(`unexpected "${toks[p].v}" in "${src}"`);
  astCache.set(src, ast);
  return ast;
}

function run(n, get) {
  if (n.n !== undefined) return n.n;
  if (n.id !== undefined) {
    if (n.id in CONST) return CONST[n.id];
    return get(n.id);
  }
  if (n.f !== undefined) {
    const f = FN[n.f];
    if (!f) throw new ExprError(`unknown function ${n.f}`);
    return f(...n.args.map((a) => run(a, get)));
  }
  if (n.o === 'neg') return -run(n.l, get);
  const a = run(n.l, get), b = run(n.r, get);
  switch (n.o) {
    case '+': return a + b; case '-': return a - b; case '*': return a * b;
    case '/': return a / b; case '%': return a % b; case '^': return Math.pow(a, b);
  }
  throw new ExprError('bad node');
}

/** Build an evaluation scope from [{name, expr}] parameters (later params may use earlier ones, any order works). */
export function makeScope(params) {
  const defs = new Map();
  for (const p of params || []) defs.set(p.name, p.expr);
  const memo = new Map();
  const stack = new Set();
  const get = (name) => {
    if (memo.has(name)) return memo.get(name);
    if (!defs.has(name)) throw new ExprError(`unknown parameter "${name}"`);
    if (stack.has(name)) throw new ExprError(`parameter cycle at "${name}"`);
    stack.add(name);
    try {
      const v = run(parse(String(defs.get(name))), get);
      if (!Number.isFinite(v)) throw new ExprError(`"${name}" is not a finite number`);
      memo.set(name, v);
      return v;
    } finally { stack.delete(name); }
  };
  const ev = (e) => {
    if (typeof e === 'number') return e;
    const v = run(parse(String(e)), get);
    if (!Number.isFinite(v)) throw new ExprError(`"${e}" is not a finite number`);
    return v;
  };
  const values = () => {
    const out = {};
    for (const n of defs.keys()) { try { out[n] = get(n); } catch (e) { out[n] = NaN; } }
    return out;
  };
  return { ev, get, values, has: (n) => defs.has(n) };
}

/** Throws ExprError if the expression cannot be parsed (does not resolve names). */
export function checkSyntax(e) { if (typeof e !== 'number') parse(String(e)); }
