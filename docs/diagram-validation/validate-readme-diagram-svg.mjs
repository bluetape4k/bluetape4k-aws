#!/usr/bin/env node
import { readFileSync } from "node:fs";

const files = process.argv.slice(2);

if (files.length === 0) {
  console.error("usage: validate-readme-diagram-svg.mjs <diagram.svg> [...]");
  process.exit(2);
}

const allowedFonts = new Set([
  "Architects Daughter",
  "Comic Mono",
  "goorm Sans",
  "goorm Sans Code",
]);
const failures = [];

function attrsOf(tag) {
  const attrs = {};
  for (const match of tag.matchAll(/([:\w-]+)\s*=\s*"([^"]*)"/g)) {
    attrs[match[1]] = match[2];
  }
  return attrs;
}

function rects(svg) {
  return [...svg.matchAll(/<rect\b[^>]*>/g)]
    .map((match) => attrsOf(match[0]))
    .map((attrs) => ({
      className: attrs.class ?? "",
      x: Number(attrs.x ?? 0),
      y: Number(attrs.y ?? 0),
      width: Number(attrs.width ?? 0),
      height: Number(attrs.height ?? 0),
    }));
}

function paths(svg) {
  return [...svg.matchAll(/<path\b[^>]*>/g)]
    .map((match) => attrsOf(match[0]))
    .filter((attrs) => (attrs.class ?? "").includes("arrow-"))
    .map((attrs) => attrs.d ?? "");
}

function numbers(tokens, cursor, count) {
  if (cursor.index + count > tokens.length) return null;
  const values = tokens.slice(cursor.index, cursor.index + count).map(Number);
  if (values.some((value) => Number.isNaN(value))) return null;
  cursor.index += count;
  return values;
}

function pathEndpoints(d) {
  const tokens = d.match(/[MLHVCSQTAZmlhvcsqtaz]|-?\d+(?:\.\d+)?/g) ?? [];
  const cursor = { index: 0 };
  let x = 0;
  let y = 0;
  let first = null;
  let last = null;

  while (cursor.index < tokens.length) {
    const command = tokens[cursor.index++];
    const lower = command.toLowerCase();
    const relative = command === lower;

    const setPoint = (nx, ny) => {
      x = relative ? x + nx : nx;
      y = relative ? y + ny : ny;
      first ??= [x, y];
      last = [x, y];
    };

    if (lower === "m" || lower === "l") {
      const values = numbers(tokens, cursor, 2);
      if (!values) return null;
      setPoint(values[0], values[1]);
    } else if (lower === "h") {
      const values = numbers(tokens, cursor, 1);
      if (!values) return null;
      x = relative ? x + values[0] : values[0];
      first ??= [x, y];
      last = [x, y];
    } else if (lower === "v") {
      const values = numbers(tokens, cursor, 1);
      if (!values) return null;
      y = relative ? y + values[0] : values[0];
      first ??= [x, y];
      last = [x, y];
    } else if (lower === "c") {
      const values = numbers(tokens, cursor, 6);
      if (!values) return null;
      setPoint(values[4], values[5]);
    } else if (lower === "s" || lower === "q") {
      const values = numbers(tokens, cursor, 4);
      if (!values) return null;
      setPoint(values[2], values[3]);
    } else if (lower === "t") {
      const values = numbers(tokens, cursor, 2);
      if (!values) return null;
      setPoint(values[0], values[1]);
    } else if (lower === "a") {
      const values = numbers(tokens, cursor, 7);
      if (!values) return null;
      setPoint(values[5], values[6]);
    } else if (lower === "z") {
      continue;
    } else {
      return null;
    }
  }

  return first && last ? { first, last } : null;
}

function attachedToRect([x, y], rect, tolerance = 1.25) {
  const left = Math.abs(x - rect.x) <= tolerance && y >= rect.y - tolerance && y <= rect.y + rect.height + tolerance;
  const right = Math.abs(x - (rect.x + rect.width)) <= tolerance && y >= rect.y - tolerance && y <= rect.y + rect.height + tolerance;
  const top = Math.abs(y - rect.y) <= tolerance && x >= rect.x - tolerance && x <= rect.x + rect.width + tolerance;
  const bottom = Math.abs(y - (rect.y + rect.height)) <= tolerance && x >= rect.x - tolerance && x <= rect.x + rect.width + tolerance;
  return left || right || top || bottom;
}

function contains(container, rect) {
  return (
    rect.x >= container.x &&
    rect.y >= container.y &&
    rect.x + rect.width <= container.x + container.width &&
    rect.y + rect.height <= container.y + container.height
  );
}

function overlaps(a, b) {
  return !(
    a.x + a.width <= b.x ||
    b.x + b.width <= a.x ||
    a.y + a.height <= b.y ||
    b.y + b.height <= a.y
  );
}

for (const file of files) {
  const svg = readFileSync(file, "utf8");
  const fileFailures = [];

  if (/Comic Sans MS|Graphviz|graphviz/.test(svg)) {
    fileFailures.push("contains forbidden Comic Sans MS or Graphviz text");
  }

  for (const match of svg.matchAll(/font-family:\s*"([^"]+)"/g)) {
    if (!allowedFonts.has(match[1])) {
      fileFailures.push(`uses unexpected font: ${match[1]}`);
    }
  }

  const allRects = rects(svg);
  const containers = allRects.filter((rect) => rect.className === "layer" || rect.className === "lane");
  const cards = allRects.filter((rect) => rect.className.includes("card-") && rect.width > 80 && rect.height > 40);
  const attachables = [...cards, ...containers];

  for (const card of cards) {
    if (!containers.some((container) => contains(container, card))) {
      fileFailures.push(`card outside layer/lane: ${card.className} @ ${card.x},${card.y},${card.width},${card.height}`);
    }
  }

  for (let i = 0; i < cards.length; i += 1) {
    for (let j = i + 1; j < cards.length; j += 1) {
      if (overlaps(cards[i], cards[j])) {
        fileFailures.push(`card overlap: ${cards[i].className} @ ${cards[i].x},${cards[i].y} with ${cards[j].className} @ ${cards[j].x},${cards[j].y}`);
      }
    }
  }

  for (const d of paths(svg)) {
    const endpoints = pathEndpoints(d);
    if (!endpoints) {
      fileFailures.push(`cannot parse arrow path: ${d}`);
      continue;
    }
    if (!attachables.some((rect) => attachedToRect(endpoints.first, rect))) {
      fileFailures.push(`arrow start is detached: ${endpoints.first.join(",")} path="${d}"`);
    }
    if (!attachables.some((rect) => attachedToRect(endpoints.last, rect))) {
      fileFailures.push(`arrow end is detached: ${endpoints.last.join(",")} path="${d}"`);
    }
  }

  if (fileFailures.length === 0) {
    console.log(`${file}: PASS`);
  } else {
    failures.push({ file, fileFailures });
    console.log(`${file}: FAIL`);
    for (const failure of fileFailures) {
      console.log(`  - ${failure}`);
    }
  }
}

if (failures.length > 0) {
  process.exit(1);
}
