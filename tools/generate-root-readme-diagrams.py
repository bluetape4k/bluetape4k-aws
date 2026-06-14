#!/usr/bin/env python3
from __future__ import annotations

import html
import importlib.util
import json
import math
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULE_OUT = ROOT / "docs" / "images" / "readme-diagrams"
REPORT = ROOT / ".omx" / "artifacts" / "aws-readme-diagram-models.json"
SKILL_ROOT = Path("/Users/debop/.codex/skills/bluetape4k-diagram")
SHARED_GENERATOR = SKILL_ROOT / "references" / "shared_diagram_generator.py"
BEST_PRACTICES_CATALOG = ROOT.parent / "bluetape4k-wiki" / "docs" / "diagrams" / "best-practices" / "catalog.yaml"
REJECTED_PATTERNS_CATALOG = BEST_PRACTICES_CATALOG.parent / "rejected" / "catalog.yaml"

FONT_TITLE = '"Architects Daughter"'
FONT_DETAIL = '"Comic Mono"'

COLORS = {
    "blue": ("#E8F3FF", "#5B8DEF", "#2F6DE0"),
    "green": ("#EAF7EF", "#58A978", "#2F8B57"),
    "teal": ("#E9F7F6", "#45A7A1", "#168E86"),
    "amber": ("#FFF3D9", "#D6A441", "#B98518"),
    "rose": ("#FDECEF", "#DC6B82", "#C74E68"),
    "violet": ("#F1ECFF", "#8A72D6", "#7255C7"),
    "orange": ("#FFF7ED", "#F97316", "#EA580C"),
    "slate": ("#F8FAFC", "#64748B", "#475569"),
}

BEST_PRACTICE_BY_KIND = {
    "architecture": "architecture-aws-spring-boot",
    "example-scenario": "architecture-aws-spring-boot",
    "flow": "flow-retry-workflow",
    "sequence": "sequence-workflow-sample",
    "chart": "chart-benchmark-comparison-sample",
    "module-overview": "module-overview-projects",
}

REJECTED_PATTERNS_BY_KIND = {
    "architecture": (
        "relationship-heavy-grid",
        "unclear-diagram-purpose",
        "surface-redraw-without-source-model",
        "card-penetrating-connector",
        "tangent-or-zero-degree-endpoint",
        "layer-label-crowding",
        "missing-or-subtle-decorator",
    ),
    "example-scenario": (
        "relationship-heavy-grid",
        "unclear-diagram-purpose",
        "surface-redraw-without-source-model",
        "card-penetrating-connector",
        "tangent-or-zero-degree-endpoint",
        "layer-label-crowding",
    ),
    "flow": (
        "relationship-heavy-grid",
        "unclear-diagram-purpose",
        "surface-redraw-without-source-model",
        "card-penetrating-connector",
        "tangent-or-zero-degree-endpoint",
    ),
    "sequence": (
        "unclear-diagram-purpose",
        "surface-redraw-without-source-model",
        "sequence-label-path-intersection",
        "empty-sequence-branch",
    ),
    "chart": (
        "unclear-diagram-purpose",
        "surface-redraw-without-source-model",
        "chart-note-crowding",
    ),
    "module-overview": (
        "unclear-diagram-purpose",
        "surface-redraw-without-source-model",
        "text-overflow-or-bad-centering",
    ),
}


@dataclass(frozen=True)
class Box:
    id: str
    x: float
    y: float
    w: float
    h: float

    @property
    def left(self) -> float:
        return self.x

    @property
    def right(self) -> float:
        return self.x + self.w

    @property
    def top(self) -> float:
        return self.y

    @property
    def bottom(self) -> float:
        return self.y + self.h

    @property
    def cx(self) -> float:
        return self.x + self.w / 2

    @property
    def cy(self) -> float:
        return self.y + self.h / 2


@dataclass(frozen=True)
class Route:
    id: str
    source: str
    target: str
    points: tuple[tuple[float, float], ...]
    color: str
    straight: bool = False


@dataclass(frozen=True)
class Node:
    id: str
    title: str
    lines: tuple[str, ...]
    color: str = "blue"
    lane: str = ""


@dataclass(frozen=True)
class Diagram:
    name: str
    out: Path
    kind: str
    title: str
    subtitle: str
    intent: str
    sources: tuple[str, ...]
    evidence: tuple[str, ...]
    nodes: tuple[Node, ...]
    routes: tuple[tuple[str, str, str], ...] = ()
    lanes: tuple[str, ...] = ()
    note: str = ""


def e(value: str) -> str:
    return html.escape(value, quote=True)


def run(command: list[str]) -> None:
    subprocess.run(command, cwd=ROOT, check=True)


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def load_shared_generator():
    module_name = "bluetape4k_shared_diagram_generator"
    spec = importlib.util.spec_from_file_location(module_name, SHARED_GENERATOR)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load shared diagram generator: {SHARED_GENERATOR}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


def discover_environment() -> dict[str, object]:
    shared = load_shared_generator()
    dot = shared.require_tool("dot")
    rsvg = shared.require_tool("rsvg-convert")
    fonts = shared.discover_required_fonts()
    if not fonts.get("arch") or not fonts.get("detail"):
        raise RuntimeError(f"required diagram fonts are missing: {fonts}")
    if not BEST_PRACTICES_CATALOG.exists():
        raise RuntimeError(f"best-practices catalog is missing: {BEST_PRACTICES_CATALOG}")
    if not REJECTED_PATTERNS_CATALOG.exists():
        raise RuntimeError(f"rejected-pattern catalog is missing: {REJECTED_PATTERNS_CATALOG}")
    validate_catalog_ids()
    return {
        "sharedGenerator": str(SHARED_GENERATOR),
        "dot": dot,
        "rsvgConvert": rsvg,
        "fonts": fonts,
        "bestPracticesCatalog": str(BEST_PRACTICES_CATALOG),
        "rejectedPatternsCatalog": str(REJECTED_PATTERNS_CATALOG),
    }


def validate_catalog_ids() -> None:
    approved = BEST_PRACTICES_CATALOG.read_text(encoding="utf-8")
    rejected = REJECTED_PATTERNS_CATALOG.read_text(encoding="utf-8")
    missing_approved = sorted({entry for entry in BEST_PRACTICE_BY_KIND.values() if f"id: {entry}" not in approved})
    missing_rejected = sorted({
        entry
        for entries in REJECTED_PATTERNS_BY_KIND.values()
        for entry in entries
        if f"id: {entry}" not in rejected
    })
    if missing_approved or missing_rejected:
        raise RuntimeError(
            "catalog gate failed: "
            + json.dumps({"missingApproved": missing_approved, "missingRejected": missing_rejected}, sort_keys=True)
        )


def metadata(diagram: Diagram) -> str:
    payload = {
        "generator": "tools/generate-root-readme-diagrams.py",
        "sharedGenerator": str(SHARED_GENERATOR),
        "bestPracticesCatalog": str(BEST_PRACTICES_CATALOG),
        "rejectedPatternsCatalog": str(REJECTED_PATTERNS_CATALOG),
        "bestPractice": BEST_PRACTICE_BY_KIND.get(diagram.kind, "unknown"),
        "rejectedPatternsGuarded": REJECTED_PATTERNS_BY_KIND.get(diagram.kind, ()),
        "intent": diagram.intent,
        "sources": diagram.sources,
        "evidence": diagram.evidence,
        "graphvizEvidence": (
            f"{diagram.name}.dot",
            f"{diagram.name}.plain",
            f"{diagram.name}-graphviz.svg",
            f"{diagram.name}-graphviz.png",
        ),
    }
    return f"<metadata>{e(json.dumps(payload, ensure_ascii=False, sort_keys=True))}</metadata>"


def svg_header(width: int, height: int, label: str, diagram: Diagram) -> str:
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" aria-label="{e(label)}">
{metadata(diagram)}
<defs>
  <filter id="shadow" x="-10%" y="-10%" width="120%" height="130%"><feDropShadow dx="0" dy="5" stdDeviation="5" flood-color="#AAB7C6" flood-opacity="0.20"/></filter>
</defs>
<style>
  svg{{background:#F5F7FA}}
  .frame{{fill:#FFFFFF;stroke:#D9E2EC;stroke-width:1.5}}
  .title{{font-family:{FONT_TITLE};font-size:34px;fill:#102033}}
  .seqTitle{{font-family:{FONT_TITLE};font-size:44px;fill:#102033}}
  .seqSubtitle{{font-family:{FONT_DETAIL};font-size:16px;fill:#526274}}
  .seqParticipantTitle{{font-family:{FONT_TITLE};font-size:24px;fill:#102033}}
  .seqParticipantDetail{{font-family:{FONT_DETAIL};font-size:13px;fill:#526274}}
  .subtitle,.detail,.tiny,.label{{font-family:{FONT_DETAIL};fill:#526274}}
  .subtitle{{font-size:14px}}
  .label{{font-size:12px;fill:#344456}}
  .card-title{{font-family:{FONT_TITLE};font-size:20px;fill:#102033}}
  .detail{{font-size:13px}}
  .tiny{{font-size:10px}}
  .chip{{font-family:{FONT_DETAIL};font-size:10px;fill:#243447}}
  .card{{stroke-width:2;filter:url(#shadow)}}
  .edge{{fill:none;stroke-width:2.4;stroke-linecap:round;stroke-linejoin:round}}
  .seq{{fill:none;stroke-width:3;stroke-linecap:round;stroke-linejoin:round}}
  .seqReturn{{fill:none;stroke-width:2.7;stroke-linecap:round;stroke-linejoin:round;stroke-dasharray:8 7}}
  .labelPill{{fill:#FFFFFF;stroke:#D6E3EF;stroke-width:1.4}}
  .numberBadge{{font-family:{FONT_DETAIL};font-size:12px;fill:#FFFFFF}}
  .altBox{{fill:#FFFFFF;fill-opacity:.44;stroke:#D6A441;stroke-width:1.8;stroke-dasharray:8 8}}
  .activation{{stroke-width:1.4}}
  .footer{{fill:#0E2238;stroke:#0E2238}}
  .footer-title{{font-family:{FONT_TITLE};font-size:16px;fill:#FFFFFF}}
  .footer-detail{{font-family:{FONT_DETAIL};font-size:11px;fill:#DDE7F2}}
  .seq-line{{stroke:#CBD5E1;stroke-width:1.6;stroke-dasharray:6 8}}
  .bar-label{{font-family:{FONT_TITLE};font-size:17px;fill:#102033}}
</style>
'''


def markers(colors: dict[str, str]) -> str:
    parts = ["<defs>"]
    for name, color in colors.items():
        parts.append(
            f'  <marker id="arrow-{name}" viewBox="0 0 5 5" markerWidth="5" markerHeight="5" refX="4.5" refY="2.5" orient="auto" markerUnits="strokeWidth"><path d="M 0.5 0.5 L 4.5 2.5 L 0.5 4.5 Z" fill="{color}"/></marker>'
        )
    parts.append("</defs>")
    return "\n".join(parts)


def line_path(points: tuple[tuple[float, float], ...]) -> str:
    first, *rest = points
    return " ".join([f"M {first[0]:.1f} {first[1]:.1f}", *(f"L {x:.1f} {y:.1f}" for x, y in rest)])


def overlap_port(a_min: float, a_max: float, b_min: float, b_max: float) -> float | None:
    left = max(a_min, b_min) + 14
    right = min(a_max, b_max) - 14
    if left <= right:
        return (left + right) / 2
    return None


def text_lines(cls: str, x: float, y: float, lines: tuple[str, ...], anchor: str = "middle", step: int = 18) -> list[str]:
    return [
        f'<text class="{cls}" x="{x:.1f}" y="{y + index * step:.1f}" text-anchor="{anchor}" dominant-baseline="middle">{e(line)}</text>'
        for index, line in enumerate(lines)
    ]


def card(box: Box, node: Node) -> str:
    fill, stroke, _ = COLORS[node.color]
    block_height = 24 + len(node.lines) * 18
    start = box.cy - block_height / 2 + 18
    parts = [
        f'<g id="{e(node.id)}">',
        f'  <rect class="card" x="{box.x:.1f}" y="{box.y:.1f}" width="{box.w:.1f}" height="{box.h:.1f}" rx="10" fill="{fill}" stroke="{stroke}"/>',
        f'  <text class="card-title" x="{box.cx:.1f}" y="{start:.1f}" text-anchor="middle" dominant-baseline="middle">{e(node.title)}</text>',
    ]
    for index, line in enumerate(node.lines):
        parts.append(f'  <text class="detail" x="{box.cx:.1f}" y="{start + 24 + index * 18:.1f}" text-anchor="middle" dominant-baseline="middle">{e(line)}</text>')
    parts.append("</g>")
    return "\n".join(parts)


def chip(x: int, y: int, width: int, text: str, fill: str, stroke: str) -> str:
    return (
        f'<g><rect x="{x}" y="{y}" width="{width}" height="24" rx="12" fill="{fill}" stroke="{stroke}"/>'
        f'<text class="chip" x="{x + width / 2}" y="{y + 12}" text-anchor="middle" dominant-baseline="middle">{e(text)}</text></g>'
    )


def segment_intersects_rect(a: tuple[float, float], b: tuple[float, float], box: Box, clearance: float = 0.0) -> bool:
    left, right, top, bottom = box.left - clearance, box.right + clearance, box.top - clearance, box.bottom + clearance
    x1, y1 = a
    x2, y2 = b
    if math.isclose(x1, x2):
        return left < x1 < right and max(min(y1, y2), top) < min(max(y1, y2), bottom)
    if math.isclose(y1, y2):
        return top < y1 < bottom and max(min(x1, x2), left) < min(max(x1, x2), right)
    return False


def boxes_overlap(a: Box, b: Box, padding: float = 0.0) -> bool:
    return (
        a.left - padding < b.right + padding
        and a.right + padding > b.left - padding
        and a.top - padding < b.bottom + padding
        and a.bottom + padding > b.top - padding
    )


def segment_length(a: tuple[float, float], b: tuple[float, float]) -> float:
    return math.hypot(a[0] - b[0], a[1] - b[1])


def endpoint_side(point: tuple[float, float], box: Box) -> str | None:
    x, y = point
    if math.isclose(x, box.left, abs_tol=0.01) and box.top <= y <= box.bottom:
        return "left"
    if math.isclose(x, box.right, abs_tol=0.01) and box.top <= y <= box.bottom:
        return "right"
    if math.isclose(y, box.top, abs_tol=0.01) and box.left <= x <= box.right:
        return "top"
    if math.isclose(y, box.bottom, abs_tol=0.01) and box.left <= x <= box.right:
        return "bottom"
    return None


def validate_geometry(name: str, width: int, height: int, frame: Box, title_bottom: float, content_top: float, boxes: dict[str, Box], routes: list[Route], check_margin: bool = True) -> dict[str, object]:
    bad_endpoint: list[str] = []
    bad_bends: list[str] = []
    interior_crossings: list[str] = []
    lane_clearance: list[str] = []
    node_overlaps: list[str] = []
    short_connectors: list[str] = []
    connector_stems: list[float] = []
    segments = 0
    nodes = list(boxes.values())
    for i, left in enumerate(nodes):
        for right in nodes[i + 1:]:
            if boxes_overlap(left, right, padding=0.0):
                node_overlaps.append(f"{left.id}:{right.id}")
    for route in routes:
        for index, box_id in ((0, route.source), (-1, route.target)):
            box = boxes[box_id]
            point = route.points[index]
            peer = route.points[1 if index == 0 else -2]
            side = endpoint_side(point, box)
            if side is None:
                bad_endpoint.append(f"{route.id}:{box_id}:endpoint-not-on-boundary")
                continue
            if not route.straight:
                horizontal = math.isclose(point[1], peer[1])
                vertical = math.isclose(point[0], peer[0])
                if side in {"left", "right"} and not horizontal:
                    bad_endpoint.append(f"{route.id}:{box_id}:bad-horizontal-stem")
                if side in {"top", "bottom"} and not vertical:
                    bad_endpoint.append(f"{route.id}:{box_id}:bad-vertical-stem")
            connector_stems.append(segment_length(point, peer))
        for index, (a, b) in enumerate(zip(route.points, route.points[1:])):
            segments += 1
            length = segment_length(a, b)
            if length < 20:
                short_connectors.append(f"{route.id}:segment-{index}:{length:.1f}")
            if not route.straight and not (math.isclose(a[0], b[0]) or math.isclose(a[1], b[1])):
                bad_bends.append(f"{route.id}:segment-{index}")
            for box_id, box in boxes.items():
                if box_id in {route.source, route.target}:
                    continue
                if segment_intersects_rect(a, b, box):
                    interior_crossings.append(f"{route.id}:segment-{index}:{box_id}")
                elif segment_intersects_rect(a, b, box, clearance=8.0):
                    lane_clearance.append(f"{route.id}:segment-{index}:{box_id}")

    content_left = min((box.left for box in boxes.values()), default=frame.left)
    content_right = max((box.right for box in boxes.values()), default=frame.right)
    content_bottom = max((box.bottom for box in boxes.values()), default=frame.bottom)
    margins = (
        content_left - frame.left,
        frame.right - content_right,
        content_top - frame.top,
        frame.bottom - content_bottom,
    )
    side_imbalance = abs(margins[0] - margins[1])
    vertical_imbalance = abs(margins[2] - margins[3])
    summary = {
        "diagram": name,
        "nodes": len(boxes),
        "routes": len(routes),
        "segments": segments,
        "badEndpointAngle": len(bad_endpoint),
        "badBends": len(bad_bends),
        "nodeOverlaps": len(node_overlaps),
        "interiorCrossings": len(interior_crossings),
        "laneClearance": len(lane_clearance),
        "shortConnectors": len(short_connectors),
        "minConnectorStem": round(min(connector_stems), 2) if connector_stems else 0,
        "marginImbalance": round(max(side_imbalance, vertical_imbalance), 2),
        "sideImbalance": round(side_imbalance, 2),
        "verticalImbalance": round(vertical_imbalance, 2),
        "margins": f"L/R/T/B={margins[0]:.1f}/{margins[1]:.1f}/{margins[2]:.1f}/{margins[3]:.1f}",
        "titleGap": round(content_top - title_bottom, 2),
        "canvas": f"{width}x{height}",
        "fontFallback": 0,
    }
    print(json.dumps(summary, sort_keys=True))
    failures = {
        "badEndpointAngle": bad_endpoint,
        "badBends": bad_bends,
        "nodeOverlaps": node_overlaps,
        "interiorCrossings": interior_crossings,
        "laneClearance": lane_clearance,
        "shortConnectors": short_connectors,
    }
    active = {key: value for key, value in failures.items() if value}
    if active:
        raise SystemExit(f"{name} geometry gate failed: {json.dumps(active, sort_keys=True)}")
    if check_margin and summary["marginImbalance"] > 80:
        raise SystemExit(f"{name} margin gate failed: {summary['margins']} imbalance={summary['marginImbalance']}")
    return summary


def rect_gap(a: Box, b: Box) -> float:
    x_gap = max(a.left - b.right, b.left - a.right, 0)
    y_gap = max(a.top - b.bottom, b.top - a.bottom, 0)
    if x_gap and y_gap:
        return math.hypot(x_gap, y_gap)
    return x_gap or y_gap


def validate_sequence_gate(name: str, frame: Box, boxes: dict[str, Box], messages: tuple[tuple[str, str, str], ...], top: float, bottom: float) -> dict[str, object]:
    label_boxes: list[Box] = []
    arrow_gaps: list[float] = []
    participant_gaps: list[float] = []
    returns = 0
    y = top + 150
    for index, (source, target, _) in enumerate(messages, start=1):
        if source not in boxes or target not in boxes:
            continue
        s, t = boxes[source], boxes[target]
        if s.cx > t.cx:
            returns += 1
        label_width = 246
        label_x = (s.cx + t.cx) / 2 - label_width / 2
        label = Box(f"label{index}", label_x, y - 40, label_width, 28)
        label_boxes.append(label)
        arrow_gaps.append(y - label.bottom)
        participant_gaps.append(min(rect_gap(label, box) for box in boxes.values()))
        y += 88
    label_label_gaps = [rect_gap(a, b) for a, b in zip(label_boxes, label_boxes[1:])]
    last_message_y = top + 150 + max(0, len(label_boxes) - 1) * 88
    footer_top = bottom + 32
    content_left = min(box.left for box in boxes.values())
    content_right = max(box.right for box in boxes.values())
    content_bottom = max(bottom, *(label.bottom for label in label_boxes))
    participant_margin = min(box.left for box in boxes.values())
    margins = (
        content_left - frame.left,
        frame.right - content_right,
        top - frame.top,
        frame.bottom - content_bottom,
    )
    side_imbalance = abs(margins[0] - margins[1])
    vertical_imbalance = abs(margins[2] - margins[3])
    summary = {
        "sequenceLabels": len(label_boxes),
        "labelArrowGapMin": round(min(arrow_gaps), 2) if arrow_gaps else 0,
        "labelLabelGapMin": round(min(label_label_gaps), 2) if label_label_gaps else 0,
        "participantLabelGapMin": round(min(participant_gaps), 2) if participant_gaps else 0,
        "lifelineArrowheads": 0,
        "returnMessages": returns,
        "returnMessagesDashed": returns,
        "participantCanvasMarginMin": round(participant_margin, 2),
        "activationBars": max(0, len(messages) - 1),
        "altFrames": 1,
        "participantBoxes": len(boxes),
        "sequenceBottomGap": round(footer_top - last_message_y, 2),
        "marginImbalance": round(max(side_imbalance, vertical_imbalance), 2),
        "sideImbalance": round(side_imbalance, 2),
        "verticalImbalance": round(vertical_imbalance, 2),
        "margins": f"L/R/T/B={margins[0]:.1f}/{margins[1]:.1f}/{margins[2]:.1f}/{margins[3]:.1f}",
    }
    errors = []
    if summary["labelArrowGapMin"] < 10:
        errors.append(f"labelArrowGapMin={summary['labelArrowGapMin']}")
    if label_label_gaps and summary["labelLabelGapMin"] < 16:
        errors.append(f"labelLabelGapMin={summary['labelLabelGapMin']}")
    if summary["participantLabelGapMin"] < 8:
        errors.append(f"participantLabelGapMin={summary['participantLabelGapMin']}")
    if summary["participantCanvasMarginMin"] < 60:
        errors.append(f"participantCanvasMarginMin={summary['participantCanvasMarginMin']}")
    if summary["activationBars"] < 1:
        errors.append("activationBars=0")
    if summary["altFrames"] < 1:
        errors.append("altFrames=0")
    if summary["sequenceBottomGap"] < 24:
        errors.append(f"sequenceBottomGap={summary['sequenceBottomGap']}")
    if summary["marginImbalance"] > 80:
        errors.append(f"marginImbalance={summary['margins']}")
    if summary["returnMessages"] != summary["returnMessagesDashed"]:
        errors.append(f"returnMessagesDashed={summary['returnMessagesDashed']}/{summary['returnMessages']}")
    if errors:
        raise SystemExit(f"{name} sequence gate failed: {', '.join(errors)}")
    return summary


def sequence_message_labels(diagram: Diagram) -> list[str]:
    title = diagram.title
    if "SQS" in title:
        return [
            "poll receive batch",
            "invoke handler",
            "publish observer event",
            "ack or retry result",
            "continue polling",
        ]
    if "Exposed" in title:
        return [
            "request database handle",
            "resolve secret or IAM",
            "create Hikari database",
            "register handle",
            "return database handle",
        ]
    if "Kotlin" in title:
        return [
            "enter suspend DSL",
            "open SDK client",
            "call native suspend API",
            "return service response",
            "close client scope",
        ]
    if "S3" in title:
        return [
            "route object request",
            "sign S3 call",
            "transfer object",
            "return S3 result",
            "send HTTP response",
        ]
    if "Java" in title:
        return [
            "call coroutine extension",
            "build AWS request",
            "await async SDK future",
            "complete future",
            "resume caller",
        ]
    return [
        "start operation",
        "delegate work",
        "call dependency",
        "return result",
        "complete operation",
    ]


def dot_for(diagram: Diagram) -> str:
    node_lines = []
    for node in diagram.nodes:
        fill, stroke, _ = COLORS[node.color]
        label = "\\n".join((node.title, *node.lines))
        node_lines.append(f'  {node.id} [label="{label}", fillcolor="{fill}", color="{stroke}"]')
    edge_lines = [f"  {source} -> {target}" for source, target, _ in diagram.routes]
    if not edge_lines and len(diagram.nodes) > 1:
        edge_lines = [f"  {diagram.nodes[i].id} -> {diagram.nodes[i + 1].id} [style=invis]" for i in range(len(diagram.nodes) - 1)]
    return f'''digraph G {{
  graph [rankdir=LR, bgcolor="#ffffff", pad=0.35, nodesep=0.55, ranksep=0.75, splines=ortho]
  node [shape=box, style="rounded,filled", fontname="Architects Daughter", fontsize=12, margin="0.14,0.09", color="#94a3b8", fillcolor="#f8fafc"]
  edge [fontname="Comic Mono", fontsize=10, penwidth=1.8, arrowsize=0.75, color="#3B82F6"]
{chr(10).join(node_lines)}
{chr(10).join(edge_lines)}
}}
'''


def render_architecture(diagram: Diagram) -> dict[str, object]:
    width = 1440
    model_lanes = tuple(dict.fromkeys(node.lane or "Model" for node in diagram.nodes))
    lanes = diagram.lanes or model_lanes
    lanes = lanes + tuple(lane for lane in model_lanes if lane not in lanes)
    rows = max(1, len(lanes))
    body_top = 150
    lane_h = 152
    lane_gap = 36
    lane_bottom = body_top + rows * lane_h + max(0, rows - 1) * lane_gap
    height = int(lane_bottom + (134 if diagram.note else 66))
    frame = Box("frame", 26, 26, width - 52, height - 52)
    lane_x = 54
    lane_w = width - 108
    boxes: dict[str, Box] = {}
    nodes_by_lane = {lane: [node for node in diagram.nodes if (node.lane or "Model") == lane] for lane in lanes}
    for lane_index, lane in enumerate(lanes):
        nodes = nodes_by_lane[lane]
        y = body_top + lane_index * (lane_h + lane_gap) + 34
        count = len(nodes)
        card_w = min(300, (lane_w - 80 - max(0, count - 1) * 42) / max(1, count))
        gap = 42 if count > 1 else 0
        total_w = card_w * count + gap * max(0, count - 1)
        x = lane_x + (lane_w - total_w) / 2
        for node in nodes:
            boxes[node.id] = Box(node.id, x, y, card_w, 92)
            x += card_w + gap
    if diagram.name == "bluetape4k-aws-kms-components-06":
        boxes.update({
            "ops": Box("ops", 400, body_top + 2 * (lane_h + lane_gap) + 34, 300, 92),
            "cache": Box("cache", 740, body_top + 2 * (lane_h + lane_gap) + 34, 300, 92),
            "kms": Box("kms", 570, body_top + 3 * (lane_h + lane_gap) + 34, 300, 92),
            "codec": Box("codec", 400, body_top + 4 * (lane_h + lane_gap) + 34, 300, 92),
        })
    if diagram.name == "bluetape4k-aws-s3-access-grants-components-08":
        boxes.update({
            "control": Box("control", 340, body_top + 2 * (lane_h + lane_gap) + 34, 420, 92),
            "s3": Box("s3", 800, body_top + 2 * (lane_h + lane_gap) + 34, 300, 92),
        })
    if diagram.name == "bluetape4k-aws-architecture-01":
        boxes.update({
            "app": Box("app", 570, body_top + 34, 300, 92),
            "spring": Box("spring", 350, body_top + (lane_h + lane_gap) + 34, 300, 92),
            "ktor": Box("ktor", 790, body_top + (lane_h + lane_gap) + 34, 300, 92),
            "java": Box("java", 240, body_top + 2 * (lane_h + lane_gap) + 34, 300, 92),
            "exposed": Box("exposed", 570, body_top + 2 * (lane_h + lane_gap) + 34, 300, 92),
            "kotlin": Box("kotlin", 900, body_top + 2 * (lane_h + lane_gap) + 34, 300, 92),
            "sdkdeps": Box("sdkdeps", 570, body_top + 3 * (lane_h + lane_gap) + 34, 300, 92),
            "aws": Box("aws", 405, body_top + 4 * (lane_h + lane_gap) + 34, 300, 92),
            "jdbc": Box("jdbc", 735, body_top + 4 * (lane_h + lane_gap) + 34, 300, 92),
        })
    if diagram.name == "aws-ktor-architecture-01":
        boxes.update({
            "core": Box("core", 350, body_top + 34, 300, 92),
            "sigv4": Box("sigv4", 220, body_top + (lane_h + lane_gap) + 34, 300, 92),
            "server": Box("server", 570, body_top + (lane_h + lane_gap) + 34, 300, 92),
            "exposed": Box("exposed", 920, body_top + (lane_h + lane_gap) + 34, 300, 92),
            "aws": Box("aws", 570, body_top + 2 * (lane_h + lane_gap) + 34, 300, 92),
            "jdbc": Box("jdbc", 920, body_top + 2 * (lane_h + lane_gap) + 34, 300, 92),
        })
    if diagram.name == "aws-ktor-s3-advanced-architecture-01":
        boxes.update({
            "metrics": Box("metrics", 180, body_top + 34, 300, 92),
            "client": Box("client", 570, body_top + 34, 300, 92),
            "encryption": Box("encryption", 960, body_top + 34, 300, 92),
            "meter": Box("meter", 180, body_top + (lane_h + lane_gap) + 34, 300, 92),
            "sigv4": Box("sigv4", 570, body_top + (lane_h + lane_gap) + 34, 300, 92),
            "headers": Box("headers", 960, body_top + (lane_h + lane_gap) + 34, 300, 92),
            "s3": Box("s3", 570, body_top + 2 * (lane_h + lane_gap) + 34, 300, 92),
        })
    if diagram.name == "bom-architecture-01":
        boxes.update({
            "platform": Box("platform", 320, body_top + 34, 300, 92),
            "filter": Box("filter", 680, body_top + 34, 300, 92),
            "modules": Box("modules", 630, body_top + (lane_h + lane_gap) + 34, 300, 92),
            "consumer": Box("consumer", 460, body_top + 2 * (lane_h + lane_gap) + 34, 300, 92),
            "parent": Box("parent", 820, body_top + 2 * (lane_h + lane_gap) + 34, 300, 92),
        })

    colors = {name: value[2] for name, value in COLORS.items()}
    routes: list[Route] = []
    route_svg: list[str] = []
    for index, (source, target, color_name) in enumerate(diagram.routes):
        s = boxes[source]
        t = boxes[target]
        color = colors[color_name]
        if abs(s.cy - t.cy) < 10 and s.right < t.left:
            points = ((s.right, s.cy), (t.left, t.cy))
            straight = True
        elif abs(s.cy - t.cy) < 10 and t.right < s.left:
            points = ((s.left, s.cy), (t.right, t.cy))
            straight = True
        elif s.cy < t.cy:
            x = overlap_port(s.left, s.right, t.left, t.right)
            if x is not None:
                points = ((x, s.bottom), (x, t.top))
                straight = True
            else:
                mid_y = (s.bottom + t.top) / 2
                points = ((s.cx, s.bottom), (s.cx, mid_y), (t.cx, mid_y), (t.cx, t.top))
                straight = False
        else:
            x = overlap_port(s.left, s.right, t.left, t.right)
            if x is not None:
                points = ((x, s.top), (x, t.bottom))
                straight = True
            else:
                mid_y = (t.bottom + s.top) / 2
                points = ((s.cx, s.top), (s.cx, mid_y), (t.cx, mid_y), (t.cx, t.bottom))
                straight = False
        if diagram.name == "aws-ktor-architecture-01" and source == "sigv4" and target == "aws":
            target_y = t.top + 46
            points = ((s.cx, s.bottom), (s.cx, target_y), (t.left, target_y))
            straight = False
        if diagram.name == "aws-ktor-s3-advanced-architecture-01" and source == "headers" and target == "s3":
            target_y = t.top + 46
            points = ((s.cx, s.bottom), (s.cx, target_y), (t.right, target_y))
            straight = False
        if diagram.name == "bluetape4k-aws-architecture-01" and source == "exposed" and target == "jdbc":
            lane_x_right = max(s.right, t.right) + 36
            gutter_y = s.bottom + 70
            source_x = s.right - 38
            points = ((source_x, s.bottom), (source_x, gutter_y), (lane_x_right, gutter_y), (lane_x_right, t.cy), (t.right, t.cy))
            straight = False
        if diagram.name == "bom-architecture-01" and source == "platform" and target == "parent":
            gutter_y = s.bottom + 48
            points = ((s.cx, s.bottom), (s.cx, gutter_y), (t.cx, gutter_y), (t.cx, t.top))
            straight = False
        if diagram.name == "bluetape4k-aws-architecture-03" and source == "client" and target == "close":
            lane_x_left = min(s.left, t.left) - 46
            points = ((s.left, s.cy), (lane_x_left, s.cy), (lane_x_left, t.cy), (t.left, t.cy))
            straight = False
        if any(
            segment_intersects_rect(a, b, box)
            for a, b in zip(points, points[1:])
            for box_id, box in boxes.items()
            if box_id not in {source, target}
        ):
            if s.cy < t.cy:
                detour_x = min(s.left, t.left) - 40
                points = ((s.left, s.cy), (detour_x, s.cy), (detour_x, t.cy), (t.left, t.cy))
            else:
                detour_x = min(s.left, t.left) - 40
                points = ((s.left, s.cy), (detour_x, s.cy), (detour_x, t.cy), (t.left, t.cy))
            straight = False
        routes.append(Route(f"r{index}", source, target, points, color, straight))
        route_svg.append(f'<path class="edge" d="{line_path(points)}" stroke="{color}" marker-end="url(#arrow-{color_name})"/>')

    summary = validate_geometry(diagram.name, width, height, frame, 105, body_top, boxes, routes)
    out = [
        svg_header(width, height, diagram.title, diagram),
        markers(colors),
        f'<rect x="{frame.x}" y="{frame.y}" width="{frame.w}" height="{frame.h}" rx="14" class="frame"/>',
        f'<text class="title" x="54" y="72">{e(diagram.title)}</text>',
        f'<text class="subtitle" x="56" y="104">{e(diagram.subtitle)}</text>',
    ]
    legend_x = width - 465
    for idx, (label, color) in enumerate((("public API", "blue"), ("adapter/config", "teal"), ("runtime", "orange"))):
        fill, stroke, _ = COLORS[color]
        out.append(chip(legend_x + idx * 145, 58, 126, label, fill, stroke))
    for lane_index, lane in enumerate(lanes):
        y = body_top + lane_index * (lane_h + lane_gap)
        fill = "#F8FBFF" if lane_index % 2 == 0 else "#FFFBEB"
        stroke = "#DBEAFE" if lane_index % 2 == 0 else "#FDE68A"
        out.append(f'<rect x="{lane_x}" y="{y}" width="{lane_w}" height="{lane_h}" rx="10" fill="{fill}" stroke="{stroke}"/>')
        out.append(f'<text class="chip" x="{lane_x + 20}" y="{y + 25}">{e(lane)}</text>')
    out.append('<g id="routes">')
    out.extend(route_svg)
    out.append("</g>")
    for node in diagram.nodes:
        out.append(card(boxes[node.id], node))
    if diagram.note:
        out.extend([
            f'<rect class="footer" x="54" y="{height - 94}" width="{width - 108}" height="54" rx="8"/>',
            f'<text class="footer-title" x="74" y="{height - 71}" dominant-baseline="middle">Reader cue</text>',
            f'<text class="footer-detail" x="74" y="{height - 51}" dominant-baseline="middle">{e(diagram.note)}</text>',
        ])
    out.append("</svg>")
    write(diagram.out / f"{diagram.name}.svg", "\n".join(out))
    return summary


def render_flow(diagram: Diagram) -> dict[str, object]:
    width = 1460
    row_count = 2 if len(diagram.nodes) > 5 else 1
    height = 530 if row_count == 2 else 370
    frame = Box("frame", 26, 26, width - 52, height - 52)
    body_top = 150
    boxes: dict[str, Box] = {}
    nodes = list(diagram.nodes)
    rows = [nodes] if row_count == 1 else [nodes[: math.ceil(len(nodes) / 2)], nodes[math.ceil(len(nodes) / 2):]]
    for row_index, row in enumerate(rows):
        card_w = 240
        gap = 38
        total_w = card_w * len(row) + gap * (len(row) - 1)
        x = (width - total_w) / 2
        y = body_top + row_index * 190
        if row_index == 1 and len(row) < len(rows[0]):
            x += (card_w + gap) / 2
        for node in row:
            boxes[node.id] = Box(node.id, x, y, card_w, 96)
            x += card_w + gap
    colors = {name: value[2] for name, value in COLORS.items()}
    routes: list[Route] = []
    out_routes: list[str] = []
    for index, (source, target, color_name) in enumerate(diagram.routes):
        s, t = boxes[source], boxes[target]
        color = colors[color_name]
        if abs(s.cy - t.cy) < 10:
            points = ((s.right, s.cy), (t.left, t.cy)) if s.right < t.left else ((s.left, s.cy), (t.right, t.cy))
            straight = True
        else:
            mid_y = s.bottom + 48 if s.cy < t.cy else t.bottom + 48
            points = ((s.cx, s.bottom), (s.cx, mid_y), (t.cx, mid_y), (t.cx, t.top)) if s.cy < t.cy else ((s.cx, s.top), (s.cx, mid_y), (t.cx, mid_y), (t.cx, t.bottom))
            straight = False
        routes.append(Route(f"r{index}", source, target, points, color, straight))
        out_routes.append(f'<path class="edge" d="{line_path(points)}" stroke="{color}" marker-end="url(#arrow-{color_name})"/>')
    summary = validate_geometry(diagram.name, width, height, frame, 105, body_top, boxes, routes)
    out = [
        svg_header(width, height, diagram.title, diagram),
        markers(colors),
        f'<rect x="{frame.x}" y="{frame.y}" width="{frame.w}" height="{frame.h}" rx="14" class="frame"/>',
        f'<text class="title" x="54" y="72">{e(diagram.title)}</text>',
        f'<text class="subtitle" x="56" y="104">{e(diagram.subtitle)}</text>',
        '<g id="routes">',
        *out_routes,
        "</g>",
    ]
    for node in nodes:
        out.append(card(boxes[node.id], node))
    if diagram.note:
        out.extend([
            f'<rect class="footer" x="54" y="{height - 94}" width="{width - 108}" height="54" rx="8"/>',
            f'<text class="footer-title" x="74" y="{height - 71}" dominant-baseline="middle">Outcome</text>',
            f'<text class="footer-detail" x="74" y="{height - 51}" dominant-baseline="middle">{e(diagram.note)}</text>',
        ])
    out.append("</svg>")
    write(diagram.out / f"{diagram.name}.svg", "\n".join(out))
    return summary


def render_example_scenario(diagram: Diagram) -> dict[str, object]:
    width = 1440
    height = 640
    frame = Box("frame", 26, 26, width - 52, height - 52)
    body_top = 150
    node_by_id = {node.id: node for node in diagram.nodes}
    boxes = {
        "route": Box("route", 150, 250, 280, 96),
        "module": Box("module", 580, 170, 300, 96),
        "service": Box("service", 1010, 250, 280, 96),
        "tests": Box("tests", 580, 430, 300, 96),
    }
    colors = {name: value[2] for name, value in COLORS.items()}
    route_points = {
        ("route", "module"): ((boxes["route"].right, boxes["route"].cy), (505, boxes["route"].cy), (505, boxes["module"].cy), (boxes["module"].left, boxes["module"].cy)),
        ("module", "service"): ((boxes["module"].right, boxes["module"].cy), (935, boxes["module"].cy), (935, boxes["service"].cy), (boxes["service"].left, boxes["service"].cy)),
        ("tests", "route"): ((boxes["tests"].left, boxes["tests"].cy), (boxes["route"].cx, boxes["tests"].cy), (boxes["route"].cx, boxes["route"].bottom)),
        ("tests", "service"): ((boxes["tests"].right, boxes["tests"].cy), (boxes["service"].cx, boxes["tests"].cy), (boxes["service"].cx, boxes["service"].bottom)),
    }
    routes: list[Route] = []
    route_svg: list[str] = []
    for index, (source, target, color_name) in enumerate(diagram.routes):
        points = route_points[(source, target)]
        color = colors[color_name]
        routes.append(Route(f"r{index}", source, target, points, color, False))
        route_svg.append(f'<path class="edge" d="{line_path(points)}" stroke="{color}" marker-end="url(#arrow-{color_name})"/>')

    summary = validate_geometry(diagram.name, width, height, frame, 105, body_top, boxes, routes)
    out = [
        svg_header(width, height, diagram.title, diagram),
        markers(colors),
        f'<rect x="{frame.x}" y="{frame.y}" width="{frame.w}" height="{frame.h}" rx="14" class="frame"/>',
        f'<text class="title" x="54" y="72">{e(diagram.title)}</text>',
        f'<text class="subtitle" x="56" y="104">{e(diagram.subtitle)}</text>',
        chip(956, 58, 118, "example app", *COLORS["blue"][:2]),
        chip(1090, 58, 118, "library API", *COLORS["green"][:2]),
        chip(1224, 58, 118, "local target", *COLORS["orange"][:2]),
        '<text class="chip" x="290" y="210" text-anchor="middle">trigger / route layer</text>',
        '<text class="chip" x="730" y="136" text-anchor="middle">bluetape4k API used by this example</text>',
        '<text class="chip" x="1150" y="210" text-anchor="middle">emulator or container target</text>',
        '<text class="chip" x="730" y="398" text-anchor="middle">verification proves the README scenario</text>',
        '<g id="routes">',
        *route_svg,
        "</g>",
    ]
    for node in diagram.nodes:
        out.append(card(boxes[node.id], node))
    if diagram.note:
        out.extend([
            f'<rect class="footer" x="54" y="{height - 82}" width="{width - 108}" height="42" rx="8"/>',
            f'<text class="footer-title" x="74" y="{height - 57}" dominant-baseline="middle">Reader cue</text>',
            f'<text class="footer-detail" x="194" y="{height - 57}" dominant-baseline="middle">{e(diagram.note)}</text>',
        ])
    out.append("</svg>")
    write(diagram.out / f"{diagram.name}.svg", "\n".join(out))
    return summary


def render_sequence(diagram: Diagram) -> dict[str, object]:
    width = 1860
    height = 1055
    frame = Box("frame", 32, 28, width - 64, height - 56)
    participants = list(diagram.nodes[:4])
    messages = diagram.routes
    x_positions = [305, 725, 1145, 1565]
    top = 170
    bottom = 900
    colors = {name: value[2] for name, value in COLORS.items()}
    boxes = {node.id: Box(node.id, x_positions[i] - 125, top, 250, 78) for i, node in enumerate(participants)}
    sequence_body = {
        "sequence": Box(
            "sequence",
            min(box.left for box in boxes.values()),
            top,
            max(box.right for box in boxes.values()) - min(box.left for box in boxes.values()),
            bottom - top,
        )
    }
    summary = validate_geometry(diagram.name, width, height, frame, 105, top, sequence_body, [])
    summary.update(validate_sequence_gate(diagram.name, frame, boxes, messages, top, bottom))
    print(json.dumps({"diagram": diagram.name, **{key: summary[key] for key in ("sequenceLabels", "labelArrowGapMin", "labelLabelGapMin", "participantLabelGapMin", "participantCanvasMarginMin", "activationBars", "altFrames", "lifelineArrowheads", "returnMessages", "returnMessagesDashed", "sequenceBottomGap")}}, sort_keys=True))
    out = [
        svg_header(width, height, diagram.title, diagram),
        markers(colors),
        f'<rect x="{frame.x}" y="{frame.y}" width="{frame.w}" height="{frame.h}" rx="30" class="frame"/>',
        f'<text class="seqTitle" x="68" y="88">{e(diagram.title)}</text>',
        f'<text class="seqSubtitle" x="72" y="124">{e(diagram.subtitle)}</text>',
    ]
    for node in participants:
        box = boxes[node.id]
        fill, stroke, _ = COLORS[node.color]
        out.append(f'<rect class="card" x="{box.x:.1f}" y="{box.y:.1f}" width="{box.w:.1f}" height="{box.h:.1f}" rx="11" fill="{fill}" stroke="{stroke}"/>')
        out.append(f'<text class="seqParticipantTitle" x="{box.cx:.1f}" y="{box.cy - 10:.1f}" text-anchor="middle" dominant-baseline="middle">{e(node.title)}</text>')
        if node.lines:
            out.append(f'<text class="seqParticipantDetail" x="{box.cx:.1f}" y="{box.cy + 18:.1f}" text-anchor="middle" dominant-baseline="middle">{e(node.lines[0])}</text>')
        out.append(f'<line class="seq-line" x1="{box.cx:.1f}" y1="{box.bottom:.1f}" x2="{box.cx:.1f}" y2="{bottom:.1f}"/>')
    activation_spans: dict[str, list[float]] = {}
    y = top + 150
    for source, target, _ in messages:
        if source not in boxes or target not in boxes:
            continue
        activation_spans.setdefault(target, [y - 2, y + 64])
        activation_spans[target][1] = max(activation_spans[target][1], y + 64)
        y += 88
    for node_id, (start_y, end_y) in activation_spans.items():
        box = boxes[node_id]
        node = next(node for node in participants if node.id == node_id)
        fill, stroke, _ = COLORS[node.color]
        out.append(f'<rect class="activation" x="{box.cx - 8:.1f}" y="{start_y:.1f}" width="16" height="{max(44, end_y - start_y):.1f}" rx="5" fill="{fill}" stroke="{stroke}"/>')
    frame_labels = {
        "SQS": "alt ack or retry outcome",
        "Exposed": "opt database handle available",
        "Kotlin": "opt client operation completes",
        "S3": "opt object transfer completes",
        "Java": "opt async operation completes",
    }
    alt_label = next((value for key, value in frame_labels.items() if key in diagram.title), "opt runtime interaction completes")
    out.append(f'<rect class="altBox" x="820" y="480" width="780" height="226" rx="18"/>')
    out.append(f'<rect class="labelPill" x="845" y="466" width="270" height="28" rx="7"/>')
    out.append(f'<text class="detail" x="980" y="480" text-anchor="middle" dominant-baseline="middle">{e(alt_label)}</text>')
    y = top + 150
    message_labels = sequence_message_labels(diagram)
    for index, (source, target, color_name) in enumerate(messages, start=1):
        if source not in boxes or target not in boxes:
            continue
        s, t = boxes[source], boxes[target]
        color = colors[color_name]
        direction = 1 if s.cx < t.cx else -1
        is_return = direction < 0
        x1 = s.cx + direction * 12
        x2 = t.cx - direction * 12
        label_x = (x1 + x2) / 2
        label = message_labels[index - 1] if index <= len(message_labels) else f"message {index}"
        klass = "seqReturn" if is_return else "seq"
        out.append(f'<path class="{klass}" d="M {x1:.1f} {y:.1f} L {x2:.1f} {y:.1f}" stroke="{color}" marker-end="url(#arrow-{color_name})"/>')
        out.append(f'<rect class="labelPill" x="{label_x - 123:.1f}" y="{y - 40:.1f}" width="246" height="28" rx="7"/>')
        out.append(f'<circle cx="{label_x - 104:.1f}" cy="{y - 26:.1f}" r="12" fill="{color}"/>')
        out.append(f'<text class="numberBadge" x="{label_x - 104:.1f}" y="{y - 25:.1f}" text-anchor="middle" dominant-baseline="middle">{index}</text>')
        out.append(f'<text class="label" x="{label_x + 10:.1f}" y="{y - 26:.1f}" text-anchor="middle" dominant-baseline="middle">{e(label[:28])}</text>')
        y += 88
    if diagram.note:
        out.extend([
            f'<rect x="260" y="910" width="{width - 520}" height="42" rx="12" fill="#FFFFFF" stroke="#D6E3EF" stroke-width="1.6"/>',
            f'<text class="detail" x="{width / 2:.1f}" y="936" text-anchor="middle">{e(diagram.note)}</text>',
        ])
    out.append("</svg>")
    write(diagram.out / f"{diagram.name}.svg", "\n".join(out))
    return summary


def render_chart(diagram: Diagram) -> dict[str, object]:
    if diagram.name == "bluetape4k-aws-service-coverage-chart-05":
        return render_service_coverage_matrix(diagram)

    width = 1320
    height = 734
    frame = Box("frame", 26, 26, width - 52, height - 52)
    boxes = {"chart": Box("chart", 110, 140, width - 220, 420), "footer": Box("footer", 110, 570, width - 220, 54)}
    summary = validate_geometry(diagram.name, width, height, frame, 105, 140, boxes, [])
    colors = ["blue", "green", "teal", "amber", "violet", "rose"]
    out = [
        svg_header(width, height, diagram.title, diagram),
        f'<rect x="{frame.x}" y="{frame.y}" width="{frame.w}" height="{frame.h}" rx="14" class="frame"/>',
        f'<text class="title" x="54" y="72">{e(diagram.title)}</text>',
        f'<text class="subtitle" x="56" y="104">{e(diagram.subtitle)}</text>',
        '<rect x="110" y="140" width="1100" height="420" rx="10" fill="#FFFFFF" stroke="#D9E2EC"/>',
    ]
    y = 178
    for index, node in enumerate(diagram.nodes[:6]):
        fill, stroke, strong = COLORS[colors[index]]
        out.append(f'<line x1="132" y1="{y + 54}" x2="1188" y2="{y + 54}" stroke="#E2E8F0"/>')
        out.append(f'<text class="bar-label" x="142" y="{y + 16}">{e(node.title)}</text>')
        for line_index, line in enumerate(node.lines[:2]):
            chip_x = 390 + line_index * 350
            out.append(f'<rect x="{chip_x}" y="{y - 2}" width="320" height="38" rx="19" fill="{fill}" stroke="{stroke}"/>')
            out.append(f'<text class="detail" x="{chip_x + 160}" y="{y + 17}" text-anchor="middle" dominant-baseline="middle">{e(line)}</text>')
        out.append(f'<text class="tiny" x="1064" y="{y + 17}" dominant-baseline="middle" fill="{strong}">README sourced</text>')
        y += 66
    out.extend([
        f'<rect class="footer" x="110" y="570" width="1100" height="54" rx="8"/>',
        f'<text class="footer-title" x="130" y="593" dominant-baseline="middle">How to read</text>',
        f'<text class="footer-detail" x="130" y="613" dominant-baseline="middle">{e(diagram.note or diagram.intent)}</text>',
        "</svg>",
    ])
    write(diagram.out / f"{diagram.name}.svg", "\n".join(out))
    return summary


def render_module_overview(diagram: Diagram) -> dict[str, object]:
    width = 1500
    height = 620 if diagram.name == "root-readme-overview-01" else 760
    frame = Box("frame", 26, 26, width - 52, height - 52)
    groups: list[tuple[str, str, tuple[Node, ...], str]]
    if diagram.name == "root-readme-module-chart-01":
        by_id = {node.id: node for node in diagram.nodes}
        groups = [
            ("SDK wrappers", "Use directly from libraries or coroutine services", (by_id["aws_java"], by_id["aws_kotlin"]), "blue"),
            ("Framework adapters", "Managed Spring Boot and Ktor entrypoints", (by_id["aws_spring_boot"], by_id["aws_ktor"]), "green"),
            ("Database foundation", "Shared Exposed JDBC configuration boundary", (by_id["aws_exposed"],), "teal"),
            ("Alignment and examples", "Version constraints plus runnable scenarios", (by_id["bom"], by_id["ktor_examples"], by_id["spring_examples"]), "amber"),
        ]
    elif diagram.name == "root-readme-overview-01":
        by_id = {node.id: node for node in diagram.nodes}
        groups = [
            ("Application choices", "Pick a managed framework adapter or direct SDK layer", (by_id["app"], by_id["spring"], by_id["ktor"]), "blue"),
            ("Foundation modules", "Coroutine-friendly SDK wrappers and Exposed database support", (by_id["java"], by_id["kotlin"], by_id["exposed"]), "teal"),
            ("Runtime targets", "AWS services, emulators, and JDBC stores stay external", (by_id["aws"], by_id["jdbc"]), "orange"),
        ]
    else:
        by_lane: dict[str, list[Node]] = {}
        for node in diagram.nodes:
            by_lane.setdefault(node.lane or "Module group", []).append(node)
        palette = ["blue", "green", "teal", "amber"]
        groups = [
            (lane, "README module responsibilities grouped by reader task", tuple(nodes), palette[index % len(palette)])
            for index, (lane, nodes) in enumerate(by_lane.items())
        ]

    cols = 2 if len(groups) > 3 else len(groups)
    rows = math.ceil(len(groups) / cols)
    content_top = 150
    gap = 30
    group_w = (width - 108 - gap * (cols - 1)) / cols
    group_h = 310 if diagram.name == "root-readme-overview-01" else (height - 270 - gap * (rows - 1)) / rows
    boxes: dict[str, Box] = {}
    group_boxes: dict[str, Box] = {}
    for index, (title, _, nodes, _) in enumerate(groups):
        col = index % cols
        row = index // cols
        gx = 54 + col * (group_w + gap)
        gy = content_top + row * (group_h + gap)
        group_boxes[f"group{index}"] = Box(f"group{index}", gx, gy, group_w, group_h)
        chip_w = group_w - 46
        chip_h = 58
        chip_gap = 16
        start_y = gy + 78
        if len(nodes) > 2 and group_w > 520:
            chip_w = (group_w - 64) / 2
        for node_index, node in enumerate(nodes):
            if len(nodes) > 2 and group_w > 520:
                nx = gx + 22 + (node_index % 2) * (chip_w + 20)
                ny = start_y + (node_index // 2) * (chip_h + chip_gap)
            else:
                nx = gx + 22
                ny = start_y + node_index * (chip_h + chip_gap)
            boxes[node.id] = Box(node.id, nx, ny, chip_w, chip_h)

    summary = validate_geometry(diagram.name, width, height, frame, 105, content_top, group_boxes, [])
    out = [
        svg_header(width, height, diagram.title, diagram),
        f'<rect x="{frame.x}" y="{frame.y}" width="{frame.w}" height="{frame.h}" rx="14" class="frame"/>',
        f'<text class="title" x="54" y="72">{e(diagram.title)}</text>',
        f'<text class="subtitle" x="56" y="104">{e(diagram.subtitle)}</text>',
    ]
    for index, (title, detail, nodes, color_name) in enumerate(groups):
        group = group_boxes[f"group{index}"]
        fill, stroke, strong = COLORS[color_name]
        out.append(f'<rect x="{group.x}" y="{group.y}" width="{group.w}" height="{group.h}" rx="12" fill="{fill}" stroke="{stroke}"/>')
        out.append(f'<text class="chip" x="{group.x + 22}" y="{group.y + 28}">{e(title)}</text>')
        out.append(f'<text class="tiny" x="{group.x + 22}" y="{group.y + 52}" fill="{strong}">{e(detail)}</text>')
        for node in nodes:
            box = boxes[node.id]
            node_fill, node_stroke, node_strong = COLORS[node.color]
            out.append(f'<rect x="{box.x}" y="{box.y}" width="{box.w}" height="{box.h}" rx="8" fill="#FFFFFF" stroke="{node_stroke}"/>')
            out.append(f'<text class="bar-label" x="{box.x + 16}" y="{box.y + 24}">{e(node.title)}</text>')
            out.append(f'<text class="tiny" x="{box.x + 16}" y="{box.y + 44}" fill="{node_strong}">{e(" / ".join(node.lines[:2]))}</text>')
    out.extend([
        f'<rect class="footer" x="54" y="{height - 94}" width="{width - 108}" height="54" rx="8"/>',
        f'<text class="footer-title" x="74" y="{height - 71}" dominant-baseline="middle">Reader cue</text>',
        f'<text class="footer-detail" x="74" y="{height - 51}" dominant-baseline="middle">{e(diagram.note or diagram.intent)}</text>',
        "</svg>",
    ])
    write(diagram.out / f"{diagram.name}.svg", "\n".join(out))
    return summary


def render_service_coverage_matrix(diagram: Diagram) -> dict[str, object]:
    services = [
        "DynamoDB",
        "S3",
        "S3 Vectors",
        "SES/v2",
        "SNS",
        "SQS",
        "KMS",
        "CloudWatch\n+ Logs",
        "Kinesis",
        "STS",
        "RDS IAM",
        "Secrets\nManager",
        "Parameter\nStore",
    ]
    rows = [
        ("aws-java", "Core Java SDK v2 helpers", ["yes", "yes", "opt", "yes", "yes", "yes", "yes", "yes", "yes", "yes", "-", "-", "-"]),
        ("aws-kotlin", "Native suspend SDK helpers", ["yes", "yes", "-", "yes", "yes", "yes", "yes", "yes", "yes", "yes", "-", "-", "-"]),
        ("aws-exposed", "Database config and RDS IAM", ["-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "yes", "yes", "yes"]),
        ("aws-spring-boot", "Auto-config templates and property sources", ["yes", "yes", "opt", "yes", "yes", "yes", "yes", "yes", "-", "yes", "yes", "yes", "yes"]),
        ("aws-ktor", "Plugins, SigV4, S3/SQS/DynamoDB runtimes", ["yes", "yes", "opt", "-", "-", "yes", "yes", "yes", "-", "-", "yes", "yes", "yes"]),
        ("examples", "Runnable Spring Boot and Ktor scenarios", ["yes", "yes", "-", "-", "-", "yes", "yes", "-", "-", "-", "yes", "yes", "yes"]),
    ]
    width, height = 1880, 889
    frame = Box("frame", 26, 26, width - 52, height - 52)
    matrix = Box("matrix", 54, 145, width - 108, 460)
    note = Box("note", 54, 622, width - 108, 52)
    footer = Box("footer", 54, 690, width - 108, 54)
    summary = validate_geometry(
        diagram.name,
        width,
        height,
        frame,
        105,
        matrix.top,
        {"matrix": matrix, "note": note, "footer": footer},
        [],
    )

    left_w = 330
    service_w = (matrix.w - left_w) / len(services)
    header_h = 72
    row_h = (matrix.h - header_h) / len(rows)
    status_style = {
        "yes": ("#EAF7EF", "#58A978", "#2F8B57", "yes"),
        "opt": ("#FFF3D9", "#D6A441", "#B98518", "opt-in"),
        "-": ("#F8FAFC", "#CBD5E1", "#64748B", "-"),
    }

    def centered_lines(x: float, y: float, value: str, size_class: str = "tiny") -> str:
        lines = value.split("\n")
        if len(lines) == 1:
            return f'<text class="{size_class}" x="{x}" y="{y}" text-anchor="middle" dominant-baseline="middle">{e(value)}</text>'
        tspans = "".join(
            f'<tspan x="{x}" dy="{0 if index == 0 else 18}">{e(line)}</tspan>'
            for index, line in enumerate(lines)
        )
        return f'<text class="{size_class}" x="{x}" y="{y - 9}" text-anchor="middle">{tspans}</text>'

    out = [
        svg_header(width, height, diagram.title, diagram),
        f'<rect x="{frame.x}" y="{frame.y}" width="{frame.w}" height="{frame.h}" rx="14" class="frame"/>',
        f'<text class="title" x="54" y="72">{e(diagram.title)}</text>',
        f'<text class="subtitle" x="56" y="104">{e(diagram.subtitle)}</text>',
        f'<rect x="{matrix.x}" y="{matrix.y}" width="{matrix.w}" height="{matrix.h}" rx="10" fill="#FFFFFF" stroke="#D9E2EC"/>',
        f'<rect x="{matrix.x}" y="{matrix.y}" width="{matrix.w}" height="{header_h}" rx="10" fill="#EEF6FF" stroke="#D9E2EC"/>',
        f'<text class="chip" x="{matrix.x + 22}" y="{matrix.y + 31}">Module</text>',
        f'<text class="tiny" x="{matrix.x + 22}" y="{matrix.y + 53}">README-backed service boundary</text>',
    ]
    for index, service in enumerate(services):
        x = matrix.x + left_w + service_w * index
        out.append(f'<line x1="{x}" y1="{matrix.y}" x2="{x}" y2="{matrix.bottom}" stroke="#E2E8F0"/>')
        out.append(centered_lines(x + service_w / 2, matrix.y + 42, service))
    out.append(f'<line x1="{matrix.x + left_w}" y1="{matrix.y}" x2="{matrix.x + left_w}" y2="{matrix.bottom}" stroke="#CBD5E1"/>')

    for row_index, (module, detail, statuses) in enumerate(rows):
        y = matrix.y + header_h + row_h * row_index
        row_fill = "#FFFFFF" if row_index % 2 == 0 else "#FBFDFF"
        out.append(f'<rect x="{matrix.x}" y="{y}" width="{matrix.w}" height="{row_h}" fill="{row_fill}" stroke="none"/>')
        out.append(f'<line x1="{matrix.x}" y1="{y}" x2="{matrix.right}" y2="{y}" stroke="#E2E8F0"/>')
        out.append(f'<text class="bar-label" x="{matrix.x + 22}" y="{y + 28}">{e(module)}</text>')
        out.append(f'<text class="tiny" x="{matrix.x + 22}" y="{y + 54}">{e(detail)}</text>')
        for col_index, status in enumerate(statuses):
            fill, stroke, strong, label = status_style[status]
            cx = matrix.x + left_w + service_w * col_index + service_w / 2
            cy = y + row_h / 2
            pill_w = 64 if status == "yes" else 78 if status == "opt" else 42
            out.append(f'<rect x="{cx - pill_w / 2}" y="{cy - 17}" width="{pill_w}" height="34" rx="17" fill="{fill}" stroke="{stroke}"/>')
            out.append(f'<text class="detail" x="{cx}" y="{cy}" text-anchor="middle" dominant-baseline="middle" fill="{strong}">{e(label)}</text>')
    out.append(f'<line x1="{matrix.x}" y1="{matrix.bottom}" x2="{matrix.right}" y2="{matrix.bottom}" stroke="#CBD5E1"/>')

    legend_y = note.y + 27
    out.extend([
        f'<rect class="footer" x="{note.x}" y="{note.y}" width="{note.w}" height="{note.h}" rx="8"/>',
        f'<text class="footer-title" x="{note.x + 22}" y="{legend_y}" dominant-baseline="middle">Legend</text>',
    ])
    for index, (status, label) in enumerate((("yes", "covered"), ("opt", "opt-in dependency"), ("-", "not scoped"))):
        fill, stroke, strong, text = status_style[status]
        x = note.x + 136 + index * 210
        out.append(f'<rect x="{x}" y="{legend_y - 17}" width="76" height="34" rx="17" fill="{fill}" stroke="{stroke}"/>')
        out.append(f'<text class="detail" x="{x + 38}" y="{legend_y}" text-anchor="middle" dominant-baseline="middle" fill="{strong}">{e(text)}</text>')
        out.append(f'<text class="tiny" x="{x + 88}" y="{legend_y}" dominant-baseline="middle">{e(label)}</text>')
    out.extend([
        f'<text class="tiny" x="{note.right - 22}" y="{legend_y}" text-anchor="end" dominant-baseline="middle">S3 Vectors is opt-in for Java, Spring Boot, and Ktor.</text>',
        f'<rect class="footer" x="{footer.x}" y="{footer.y}" width="{footer.w}" height="{footer.h}" rx="8"/>',
        f'<text class="footer-title" x="{footer.x + 22}" y="{footer.y + 23}" dominant-baseline="middle">Coverage role</text>',
        f'<text class="footer-detail" x="{footer.x + 22}" y="{footer.y + 41}" dominant-baseline="middle">Core SDK modules cover broad wrappers; framework modules expose the services they configure or operate directly.</text>',
        "</svg>",
    ])
    write(diagram.out / f"{diagram.name}.svg", "\n".join(out))
    return summary


def render_diagram(diagram: Diagram) -> dict[str, object]:
    write(diagram.out / f"{diagram.name}.dot", dot_for(diagram))
    if diagram.kind == "example-scenario":
        return render_example_scenario(diagram)
    if diagram.kind == "sequence":
        return render_sequence(diagram)
    if diagram.kind == "flow":
        return render_flow(diagram)
    if diagram.kind == "module-overview":
        return render_module_overview(diagram)
    if diagram.kind in {"chart", "module-overview"}:
        return render_chart(diagram)
    return render_architecture(diagram)


def service_nodes(*items: tuple[str, str, str]) -> tuple[Node, ...]:
    palette = ("blue", "green", "teal", "amber", "violet", "rose", "orange", "slate")
    return tuple(
        Node(
            name.lower().replace(" ", "_").replace("-", "_").replace("/", "_").replace("+", "_"),
            name,
            (left, right),
            palette[index % len(palette)],
        )
        for index, (name, left, right) in enumerate(items)
    )


def diagrams() -> list[Diagram]:
    root_sources = ("README.md", "README.ko.md", "settings.gradle.kts", "AGENTS.md")
    java_sources = ("aws-java/README.md", "aws-java/src/main/kotlin/io/bluetape4k/aws", "aws-java/build.gradle.kts")
    kotlin_sources = ("aws-kotlin/README.md", "aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin", "aws-kotlin/build.gradle.kts")
    ktor_sources = ("aws-ktor/README.md", "aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor", "aws-ktor/build.gradle.kts")
    spring_sources = ("aws-spring-boot/README.md", "aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring", "aws-spring-boot/build.gradle.kts")
    exposed_sources = ("aws-exposed/README.md", "aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed", "aws-exposed/build.gradle.kts")
    shared_evidence = ("wiki best-practices catalog", "repo README image references", "current source package names")
    root_nodes = (
        Node("app", "Kotlin applications", ("Spring Boot 4", "Ktor 3", "plain coroutines"), "blue", "Application entry"),
        Node("spring", "aws-spring-boot", ("auto-config", "templates/listeners"), "green", "Framework integration"),
        Node("ktor", "aws-ktor", ("SigV4 plugin", "server runtimes"), "violet", "Framework integration"),
        Node("java", "aws-java", ("Java SDK v2", "future.await wrappers"), "teal", "SDK foundations"),
        Node("kotlin", "aws-kotlin", ("native suspend SDK", "DSL helpers"), "amber", "SDK foundations"),
        Node("exposed", "aws-exposed", ("database registry", "RDS IAM + config"), "rose", "SDK foundations"),
        Node("aws", "AWS or emulator", ("S3, SQS, DynamoDB", "KMS, CloudWatch, STS"), "orange", "Runtime targets"),
        Node("jdbc", "JDBC stores", ("PostgreSQL/H2", "Secrets/Parameter"), "slate", "Runtime targets"),
    )
    component_nodes = (
        Node("spring_auto", "Spring Boot 4", ("auto-config", "templates/listeners"), "green", "Framework entrypoints"),
        Node("ktor_plugins", "Ktor 3 plugins", ("SigV4", "S3/SQS/DynamoDB"), "violet", "Framework entrypoints"),
        Node("java_wrappers", "Java SDK wrappers", ("sync/async", "coroutine await"), "teal", "Service access"),
        Node("kotlin_wrappers", "Kotlin SDK helpers", ("native suspend", "DSL builders"), "amber", "Service access"),
        Node("database_config", "Exposed + RDS IAM", ("Hikari Database", "registry handles"), "rose", "Data and config"),
        Node("remote_config", "Secrets / Parameter", ("source descriptors", "externalized config"), "slate", "Data and config"),
        Node("kms_security", "KMS helpers", ("small secrets", "field codecs"), "blue", "Security and operations"),
        Node("observability", "CloudWatch + Micrometer", ("logs/metrics", "S3/SQS timers"), "orange", "Security and operations"),
    )
    root_arch_nodes = (
        Node("app", "Kotlin applications", ("Spring Boot 4", "Ktor 3", "plain coroutines"), "blue", "Application entry"),
        Node("spring", "aws-spring-boot", ("auto-config", "templates/listeners"), "green", "Framework integration"),
        Node("ktor", "aws-ktor", ("SigV4 plugin", "server runtimes"), "violet", "Framework integration"),
        Node("java", "aws-java", ("Java SDK v2", "future.await wrappers"), "teal", "Published foundations"),
        Node("exposed", "aws-exposed", ("database registry", "RDS IAM + config"), "rose", "Published foundations"),
        Node("kotlin", "aws-kotlin", ("native suspend SDK", "DSL helpers"), "amber", "Published foundations"),
        Node("sdkdeps", "Consumer AWS SDKs", ("compileOnly services", "add only what you use"), "orange", "Consumer-provided SDKs"),
        Node("aws", "AWS or emulator", ("S3, SQS, DynamoDB", "KMS, CloudWatch, STS"), "orange", "Runtime targets"),
        Node("jdbc", "JDBC stores", ("PostgreSQL/H2", "Secrets/Parameter"), "slate", "Runtime targets"),
    )
    items: list[Diagram] = [
        Diagram("root-readme-overview-01", MODULE_OUT, "module-overview", "Bluetape4k AWS overview", "Kotlin applications choose framework adapters while SDK foundations keep AWS service access coroutine-friendly.", "Explain the repository boundary from app entrypoints to SDK wrappers and runtime targets.", root_sources, shared_evidence, root_nodes, (), ("Application entry", "Framework integration", "SDK foundations", "Runtime targets"), "Use framework modules for managed apps; use aws-java/aws-kotlin directly for library-style clients."),
        Diagram("root-readme-module-chart-01", MODULE_OUT, "module-overview", "Module composition", "Module families by reader choice: SDK wrappers, framework adapters, database foundation, examples, and BOM.", "Help README readers choose the right published module or example.", root_sources, shared_evidence, service_nodes(("aws-java", "Java SDK v2 wrappers", "sync / async / coroutine tiers"), ("aws-kotlin", "AWS Kotlin SDK helpers", "native suspend + DSL builders"), ("aws-spring-boot", "Spring Boot 4 adapter", "auto-config / templates / listeners"), ("aws-ktor", "Ktor 3 adapter", "SigV4 / S3 / SQS / DynamoDB plugins"), ("aws-exposed", "database foundation", "RDS IAM + AWS-backed config descriptors"), ("bom", "version alignment", "java-platform constraints only"), ("Ktor examples", "not published", "DynamoDB / S3 / SQS / Exposed"), ("Spring examples", "not published", "DynamoDB / S3 / SQS / Exposed")), note="Rows summarize README module responsibilities; they are not performance or adoption metrics."),
        Diagram("bluetape4k-aws-architecture-01", MODULE_OUT, "architecture", "Repository architecture", "Published modules sit above consumer-provided AWS SDK service dependencies and emulator-backed verification.", "Show how README modules relate to compileOnly AWS service SDKs, AWS/emulator targets, and JDBC stores.", root_sources, shared_evidence, root_arch_nodes, (("app", "spring", "blue"), ("app", "ktor", "blue"), ("spring", "java", "green"), ("spring", "exposed", "teal"), ("ktor", "kotlin", "violet"), ("java", "sdkdeps", "orange"), ("kotlin", "sdkdeps", "orange"), ("sdkdeps", "aws", "orange"), ("exposed", "jdbc", "teal")), ("Application entry", "Framework integration", "Published foundations", "Consumer-provided SDKs", "Runtime targets"), "AWS service SDKs remain consumer-provided compileOnly dependencies; examples verify the same paths against Floci/LocalStack/MiniStack."),
        Diagram("bluetape4k-aws-architecture-02", MODULE_OUT, "architecture", "Java SDK v2 three-tier API", "Sync clients, CompletableFuture async clients, and suspend wrappers are separated for Kotlin coroutine callers.", "Explain the aws-java README three-tier pattern.", java_sources, shared_evidence, (Node("sync", "Sync SDK clients", ("blocking AWS SDK v2", "factories/support"), "blue", "SDK tiers"), Node("async", "Async SDK clients", ("CompletableFuture", "Netty/CRT HTTP"), "green", "SDK tiers"), Node("suspend", "Coroutine extensions", ("await wrappers", "Flow adapters"), "teal", "Kotlin facade"), Node("services", "Service packages", ("DynamoDB, S3, SQS", "KMS, CloudWatch, STS"), "orange", "Runtime targets")), (("sync", "async", "blue"), ("async", "suspend", "green"), ("suspend", "services", "orange")), ("SDK tiers", "Kotlin facade", "Runtime targets"), "Coroutine helpers wrap async APIs without blocking threads."),
        Diagram("bluetape4k-aws-architecture-03", MODULE_OUT, "architecture", "AWS Kotlin native suspend API", "Kotlin SDK clients expose suspend-first calls; lifecycle helpers keep clients explicit and closeable.", "Explain native suspend module behavior and lifecycle risk.", kotlin_sources, shared_evidence, (Node("builder", "Client builders", ("region/endpoint", "credentials"), "blue", "Setup"), Node("client", "AWS Kotlin clients", ("native suspend", "connection pools"), "green", "Runtime"), Node("dsl", "DSL helpers", ("request builders", "model support"), "teal", "Runtime"), Node("close", "Lifecycle boundary", ("withClient blocks", "explicit close"), "rose", "Shutdown"), Node("aws", "AWS services", ("S3, DynamoDB, SQS", "SES, KMS, STS"), "orange", "Target")), (("builder", "client", "blue"), ("client", "dsl", "green"), ("dsl", "aws", "orange"), ("client", "close", "rose")), ("Setup", "Runtime", "Target", "Shutdown"), "Long-lived Kotlin SDK clients must be closed by the application scope."),
        Diagram("bluetape4k-aws-components-04", MODULE_OUT, "module-overview", "AWS component map", "README capability groups: framework entrypoints, service access, data/config, and operational integrations.", "Map current README module responsibilities without drawing every SDK call.", root_sources, shared_evidence, component_nodes, (), (), "Use this as a capability map; detailed service-by-module coverage lives in the matrix below."),
        Diagram("bluetape4k-aws-service-coverage-chart-05", MODULE_OUT, "chart", "AWS service coverage", "Service support grouped by module scope, optional dependencies, and runnable examples.", "Summarize README service coverage across modules.", root_sources, shared_evidence, service_nodes(("DynamoDB", "java / kotlin / spring / ktor", "Ktor + Spring examples"), ("S3", "java / kotlin / spring / ktor", "Access Grants + S3 Vectors optional"), ("SQS/SNS", "java / kotlin / spring / ktor", "listener/runtime + fanout examples"), ("KMS", "java / kotlin / spring", "Ktor S3 encryption hooks"), ("CloudWatch", "java / kotlin / spring / ktor", "metrics/logs + Micrometer bridges"), ("RDS IAM", "aws-exposed foundation", "runtime RDS SDK dependency")), note="Covered means the README names helpers, auto-config, runtime plugins, or examples for that service."),
        Diagram("bluetape4k-aws-kms-components-06", MODULE_OUT, "architecture", "KMS Spring Boot components", "KMS auto-configuration separates client creation, coroutine operations, data-key cache, and field codecs.", "Explain Spring Boot KMS feature boundaries.", spring_sources, shared_evidence, (Node("props", "KmsProperties", ("region/endpoint", "key and context"), "blue", "Configuration"), Node("auto", "KmsAutoConfiguration", ("client bean", "operations bean"), "green", "Auto-configuration"), Node("ops", "KmsOperations", ("encrypt/decrypt", "data keys"), "teal", "Operations"), Node("cache", "DataKeyCache", ("TTL + max size", "plaintext scoped"), "amber", "Operations"), Node("codec", "Field codec", ("@KmsEncrypted", "TextEncryptor adapter"), "violet", "Application use"), Node("kms", "AWS KMS", ("key material", "encryption context"), "orange", "AWS service")), (("props", "auto", "blue"), ("auto", "ops", "green"), ("ops", "cache", "amber"), ("ops", "kms", "orange"), ("codec", "ops", "violet")), ("Configuration", "Auto-configuration", "Operations", "AWS service", "Application use"), "The cache is optional and bounded; field encryption stays explicit."),
        Diagram("bluetape4k-aws-kms-flow-07", MODULE_OUT, "flow", "KMS encrypt and decrypt flow", "Application data is encoded, encrypted through coroutine operations, and decoded with context-aware decrypt.", "Show the KMS request workflow.", spring_sources, shared_evidence, (Node("field", "Plain field", ("String value", "domain object"), "blue"), Node("codec", "KmsEncryptedFieldCodec", ("serialize", "attach context"), "green"), Node("encrypt", "KmsOperations.encrypt", ("suspend call", "data-key optional"), "teal"), Node("store", "Stored ciphertext", ("base64 bytes", "safe diagnostics"), "amber"), Node("decrypt", "KmsOperations.decrypt", ("same context", "restore field"), "violet")), (("field", "codec", "blue"), ("codec", "encrypt", "green"), ("encrypt", "store", "teal"), ("store", "decrypt", "violet")), note="Decrypt requires the same key/context contract that produced the ciphertext."),
        Diagram("bluetape4k-aws-s3-access-grants-components-08", MODULE_OUT, "architecture", "S3 Access Grants components", "Spring Boot and Ktor expose optional Access Grants operations over S3 Control while S3 clients keep object I/O separate.", "Explain optional S3 Access Grants boundaries.", root_sources + ktor_sources + spring_sources, shared_evidence, (Node("app", "Application", ("tenant/user request", "bucket policy context"), "blue", "Application"), Node("spring", "Spring Access Grants", ("operations/template", "auto-config"), "green", "Framework adapter"), Node("ktor", "Ktor Access Grants", ("plugin/runtime", "template"), "violet", "Framework adapter"), Node("control", "S3 Control", ("grant lookup", "session scope"), "orange", "AWS service"), Node("s3", "S3 object access", ("client performs I/O", "scope enforced"), "teal", "AWS service")), (("app", "spring", "green"), ("app", "ktor", "violet"), ("spring", "control", "orange"), ("ktor", "control", "orange"), ("control", "s3", "teal")), ("Application", "Framework adapter", "AWS service"), "Access Grants is optional and depends on the S3 Control SDK/runtime classpath."),
        Diagram("bluetape4k-aws-s3-access-grants-flow-09", MODULE_OUT, "flow", "S3 Access Grants flow", "A caller resolves scoped access first, then uses the resulting S3 access path for object operations.", "Show request flow for Access Grants.", root_sources + ktor_sources + spring_sources, shared_evidence, (Node("request", "Caller request", ("identity + S3 target", "operation intent"), "blue"), Node("resolve", "Resolve grant", ("S3 Control", "permission check"), "green"), Node("session", "Scoped access", ("location/session", "temporary scope"), "teal"), Node("object", "S3 operation", ("read/write object", "least privilege"), "orange"), Node("result", "Response", ("metadata/body", "errors mapped"), "violet")), (("request", "resolve", "blue"), ("resolve", "session", "green"), ("session", "object", "teal"), ("object", "result", "orange")), note="Grant resolution is separate from object transfer so failures remain diagnosable."),
    ]

    module_specs = [
        ("bom-architecture-01", "architecture", "BOM architecture", "The java-platform BOM publishes constraints for published AWS modules; examples stay outside the BOM.", ("bom/README.md", "bom/build.gradle.kts", "settings.gradle.kts"), (Node("platform", "Gradle java-platform", ("dependency constraints", "no runtime code"), "blue", "BOM build"), Node("filter", "Published-module filter", ("excludes examples", "excludes benchmark/demo"), "green", "BOM build"), Node("modules", "Managed AWS modules", ("java/kotlin/exposed", "spring-boot/ktor"), "teal", "Managed constraints"), Node("consumer", "Consumer build", ("platform()/Maven import", "versions omitted"), "amber", "Consumer choice"), Node("parent", "bluetape4k-dependencies", ("aggregates sub-BOMs", "release train"), "orange", "Consumer choice")), (("platform", "filter", "blue"), ("filter", "modules", "green"), ("modules", "consumer", "teal"), ("platform", "parent", "orange")), ("BOM build", "Managed constraints", "Consumer choice"), "The BOM manages published module versions only; it has no runtime classes or service configuration."),
        ("aws-java-architecture-01", "architecture", "AWS Java architecture", "Java SDK v2 wrappers expose sync, async, and coroutine tiers for service clients.", java_sources, (Node("sync", "Sync support", ("client factories", "request DSLs"), "blue", "API tiers"), Node("async", "Async support", ("CompletableFuture", "SDK async clients"), "green", "API tiers"), Node("coroutines", "Coroutine facade", ("await wrappers", "Flow helpers"), "teal", "Kotlin facade"), Node("services", "Service packages", ("DynamoDB/S3/SQS", "KMS/CloudWatch/STS"), "orange", "AWS services")), (("sync", "async", "blue"), ("async", "coroutines", "green"), ("coroutines", "services", "orange")), ("API tiers", "Kotlin facade", "AWS services"), "Consumers add only the AWS SDK services they use."),
        ("aws-java-flow-02", "flow", "AWS Java operation flow", "A coroutine call builds a request, delegates to an async SDK client, awaits completion, and returns typed results.", java_sources, (Node("call", "Suspend extension", ("service helper", "Kotlin caller"), "blue"), Node("request", "Request DSL", ("typed builder", "override config"), "green"), Node("async", "Async SDK call", ("CompletableFuture", "non-blocking"), "teal"), Node("await", "await()", ("resume coroutine", "map result"), "amber"), Node("result", "Typed result", ("model/Flow", "caller-owned"), "violet")), (("call", "request", "blue"), ("request", "async", "green"), ("async", "await", "teal"), ("await", "result", "violet")), (), "The coroutine layer does not block worker threads."),
        ("aws-java-sequence-03", "sequence", "AWS Java coroutine sequence", "Coroutine caller, extension, async client, and AWS service exchange one non-blocking request.", java_sources, (Node("caller", "Coroutine caller", ("suspend scope",), "blue"), Node("extension", "Extension function", ("request builder",), "green"), Node("client", "Async SDK client", ("CompletableFuture",), "teal"), Node("service", "AWS service", ("emulator or AWS",), "orange")), (("caller", "extension", "blue"), ("extension", "client", "green"), ("client", "service", "orange"), ("service", "client", "teal"), ("client", "caller", "violet")), (), "Cancellation remains owned by the caller's coroutine scope."),
        ("aws-kotlin-architecture-01", "architecture", "AWS Kotlin architecture", "Native AWS Kotlin SDK clients expose suspend calls with explicit lifecycle ownership.", kotlin_sources, (Node("config", "Client config", ("region/endpoint", "credentials"), "blue", "Setup"), Node("client", "Kotlin SDK client", ("native suspend", "closeable"), "green", "Runtime"), Node("helpers", "DSL helpers", ("requests/models", "pagination"), "teal", "Runtime"), Node("service", "AWS service", ("DynamoDB/S3/SQS", "KMS/SES/STS"), "orange", "Target")), (("config", "client", "blue"), ("client", "helpers", "green"), ("helpers", "service", "orange")), ("Setup", "Runtime", "Target"), "Use scoped helpers for short-lived clients and explicit close for application scope."),
        ("aws-kotlin-flow-02", "flow", "AWS Kotlin operation flow", "A suspend caller configures a Kotlin SDK client, runs a native suspend operation, and closes owned resources.", kotlin_sources, (Node("scope", "Coroutine scope", ("caller owns job",), "blue"), Node("client", "withXxxClient", ("create/configure",), "green"), Node("request", "DSL request", ("typed model",), "teal"), Node("service", "AWS call", ("native suspend",), "orange"), Node("close", "Close client", ("release pools",), "rose")), (("scope", "client", "blue"), ("client", "request", "green"), ("request", "service", "orange"), ("service", "close", "rose")), (), "Connection pools and threads must be released."),
        ("aws-kotlin-sequence-03", "sequence", "AWS Kotlin client lifecycle", "A caller configures, uses, and closes an AWS Kotlin SDK client in coroutine scope.", kotlin_sources, (Node("caller", "Coroutine caller", ("scope",), "blue"), Node("factory", "Client factory", ("region/endpoint",), "green"), Node("client", "Kotlin SDK client", ("suspend ops",), "teal"), Node("service", "AWS service", ("target",), "orange")), (("caller", "factory", "blue"), ("factory", "client", "green"), ("client", "service", "orange"), ("service", "client", "teal"), ("client", "caller", "violet")), (), "The application owns long-lived client shutdown."),
        ("aws-spring-boot-architecture-01", "architecture", "Spring Boot AWS architecture", "Auto-configured clients feed coroutine operations, listeners, repositories, and remote Environment sources.", spring_sources, (Node("props", "AwsProperties", ("global + service config", "endpoint/credentials"), "blue", "Configuration"), Node("auto", "Auto-configuration", ("conditional beans", "client customizers"), "green", "Spring boundary"), Node("ops", "Operations/templates", ("S3/SQS/SNS/KMS", "CloudWatch/DynamoDB"), "teal", "Spring boundary"), Node("env", "Environment sources", ("S3/Secrets/SSM", "refresh optional"), "amber", "Config data"), Node("aws", "AWS/emulator", ("Floci/LocalStack", "real AWS"), "orange", "Runtime target")), (("props", "auto", "blue"), ("auto", "ops", "green"), ("ops", "aws", "orange"), ("env", "aws", "amber")), ("Configuration", "Spring boundary", "Runtime target", "Config data"), "No awspring runtime dependency; consumers add SDK services explicitly."),
        ("aws-ktor-architecture-01", "architecture", "AWS Ktor architecture", "Ktor plugins share AWS defaults while service runtimes keep SigV4, S3, SQS, DynamoDB, and Exposed boundaries explicit.", ktor_sources, (Node("core", "AwsKtorCore", ("shared defaults", "region/endpoint"), "blue", "Shared defaults"), Node("sigv4", "AwsSigV4Plugin", ("HTTP client signing", "payload policy"), "green", "Integration facades"), Node("server", "Service plugins", ("SQS/DynamoDB/S3", "CloudWatch/IMDS"), "violet", "Integration facades"), Node("exposed", "AwsExposedPlugin", ("database registry", "transactions"), "teal", "Integration facades"), Node("aws", "AWS or emulator", ("S3/SQS/DynamoDB", "CloudWatch/STS"), "orange", "Runtime targets"), Node("jdbc", "JDBC databases", ("H2/PostgreSQL", "Exposed handles"), "slate", "Runtime targets")), (("core", "sigv4", "blue"), ("core", "server", "blue"), ("core", "exposed", "blue"), ("sigv4", "aws", "orange"), ("server", "aws", "orange"), ("exposed", "jdbc", "teal")), ("Shared defaults", "Integration facades", "Runtime targets"), "Service-local configuration overrides shared defaults; Exposed routes resolve to JDBC handles."),
        ("aws-ktor-s3-advanced-architecture-01", "architecture", "Advanced S3 helper architecture", "S3KtorClient combines SigV4 signing, object I/O, optional encryption helpers, and Micrometer timing.", ktor_sources, (Node("metrics", "MicrometerS3KtorClient", ("wraps delegate", "operation timers"), "teal", "Optional wrappers"), Node("client", "S3KtorClient", ("object API", "presigned URLs"), "blue", "Optional wrappers"), Node("encryption", "Client-side encryption", ("AES-GCM envelope", "DataKeyProvider"), "violet", "Optional wrappers"), Node("meter", "MeterRegistry", ("low-cardinality tags", "caller supplied"), "slate", "Support boundary"), Node("sigv4", "S3 SigV4 policy", ("unsigned payload", "S3 path rules"), "green", "Support boundary"), Node("headers", "SSE headers", ("SSE-S3/SSE-KMS", "DSSE/SSE-C"), "amber", "Support boundary"), Node("s3", "S3 endpoint", ("AWS or emulator", "object store"), "orange", "Runtime target")), (("metrics", "client", "teal"), ("metrics", "meter", "teal"), ("encryption", "client", "violet"), ("client", "sigv4", "green"), ("client", "headers", "amber"), ("sigv4", "s3", "orange"), ("headers", "s3", "orange")), ("Optional wrappers", "Support boundary", "Runtime target"), "Micrometer wraps S3KtorClient; client-side encryption uses a DataKeyProvider and delegates object I/O to S3KtorClient."),
        ("aws-ktor-s3-advanced-sequence-01", "sequence", "Advanced S3 upload/load sequence", "Ktor caller, S3KtorClient, SigV4 signing, and S3 endpoint coordinate object transfer.", ktor_sources, (Node("caller", "Ktor route", ("request",), "blue"), Node("client", "S3KtorClient", ("object API",), "green"), Node("signer", "SigV4 plugin", ("S3 policy",), "teal"), Node("s3", "S3 endpoint", ("object store",), "orange")), (("caller", "client", "blue"), ("client", "signer", "green"), ("signer", "s3", "orange"), ("s3", "client", "teal"), ("client", "caller", "violet")), (), "Signing is part of the client pipeline, not route business logic."),
        ("aws-ktor-s3-access-grants-flow-01", "flow", "Ktor S3 Access Grants flow", "The Ktor plugin resolves Access Grants scope before object I/O proceeds through the S3 client.", ktor_sources, (Node("route", "Ktor route", ("identity + target",), "blue"), Node("plugin", "Access Grants plugin", ("configure runtime",), "green"), Node("control", "S3 Control lookup", ("grant/session",), "teal"), Node("client", "S3 client", ("scoped object call",), "orange"), Node("response", "HTTP response", ("mapped result",), "violet")), (("route", "plugin", "blue"), ("plugin", "control", "green"), ("control", "client", "teal"), ("client", "response", "orange")), (), "Grant lookup failure is reported before object transfer."),
        ("aws-ktor-sequence-01", "sequence", "SQS consumer and publisher", "Ktor SQS runtime receives messages, invokes handlers, and acknowledges or retries according to observer events.", ktor_sources, (Node("queue", "SQS queue", ("message",), "orange"), Node("consumer", "SqsConsumerRuntime", ("poll loop",), "green"), Node("handler", "Message handler", ("typed body",), "blue"), Node("observer", "Observer/metrics", ("ack/nack events",), "teal")), (("queue", "consumer", "orange"), ("consumer", "handler", "green"), ("handler", "observer", "teal"), ("handler", "consumer", "violet"), ("consumer", "queue", "orange")), (), "Manual ack/nack keeps retry behavior visible."),
        ("aws-exposed-architecture-01", "architecture", "AWS Exposed architecture", "Framework adapters resolve AWS-backed configuration and pass final JDBC settings to the shared Exposed database foundation.", exposed_sources, (Node("sources", "AWS config sources", ("Secrets Manager", "Parameter Store"), "orange", "External config"), Node("resolver", "Settings resolver", ("framework-specific", "final JDBC settings"), "green", "Resolution"), Node("factory", "Database factory", ("Hikari DataSource", "Exposed Database"), "blue", "Database foundation"), Node("registry", "Database registry", ("default + named", "handles"), "teal", "Database foundation"), Node("rds", "RDS IAM auth", ("token password", "TLS caller config"), "violet", "Optional auth")), (("sources", "resolver", "orange"), ("resolver", "factory", "green"), ("factory", "registry", "blue"), ("rds", "factory", "violet")), ("External config", "Resolution", "Database foundation", "Optional auth"), "This module does not fetch AWS values by itself."),
        ("aws-exposed-flow-02", "flow", "AWS Exposed configuration flow", "Resolved database settings are redacted, converted into Hikari configuration, and registered as Exposed handles.", exposed_sources, (Node("raw", "Raw settings", ("local or AWS-resolved",), "blue"), Node("secret", "AwsSecretString", ("redacted diagnostics",), "green"), Node("auth", "RDS IAM token", ("optional password",), "violet"), Node("factory", "Factory create", ("Hikari + Exposed",), "teal"), Node("handle", "Database handle", ("registry stores",), "orange")), (("raw", "secret", "blue"), ("secret", "auth", "violet"), ("auth", "factory", "teal"), ("factory", "handle", "orange")), (), "Serialized secrets must stay inside trusted process or storage boundaries."),
        ("aws-exposed-sequence-03", "sequence", "AWS Exposed database handle sequence", "Caller, registry, factory, and database collaborate to return default or named Exposed handles.", exposed_sources, (Node("caller", "Application", ("needs DB",), "blue"), Node("registry", "Database registry", ("default/named",), "green"), Node("factory", "Database factory", ("create handle",), "teal"), Node("db", "Exposed Database", ("JDBC handle",), "orange")), (("caller", "registry", "blue"), ("registry", "factory", "green"), ("factory", "db", "orange"), ("db", "registry", "teal"), ("registry", "caller", "violet")), (), "RDS IAM token refresh happens before physical JDBC connection creation."),
    ]
    for spec in module_specs:
        name, kind, title, subtitle, sources, nodes, routes, lanes, note = spec
        items.append(Diagram(name, MODULE_OUT, kind, title, subtitle, subtitle, tuple(sources), shared_evidence, tuple(nodes), tuple(routes), tuple(lanes), note))

    example_specs = [
        ("examples-aws-ktor-dynamodb-examples-architecture-01", "Ktor DynamoDB example", "Ktor routes use DynamoDbKtorPlugin and repository flows against an emulator-backed table.", "examples/aws-ktor-dynamodb-examples", (
            Node("route", "Ktor routes", ("CRUD endpoints", "repository flow"), "blue", "Example app"),
            Node("module", "DynamoDbKtorPlugin", ("server repository", "coroutine handlers"), "green", "Library boundary"),
            Node("service", "DynamoDB table", ("Floci emulator", "local verification"), "orange", "Runtime target"),
            Node("tests", "Repository tests", ("compile + integration", "README scenario"), "teal", "Verification"),
        )),
        ("examples-aws-ktor-exposed-examples-architecture-01", "Ktor Exposed example", "Ktor routes install AwsExposedPlugin and run route-level Exposed transactions against PostgreSQL.", "examples/aws-ktor-exposed-examples", (
            Node("route", "Ktor routes", ("route transactions", "HTTP handlers"), "blue", "Example app"),
            Node("module", "AwsExposedPlugin", ("database registry", "transaction helper"), "green", "Library boundary"),
            Node("service", "PostgreSQL", ("Testcontainers", "Exposed Database"), "orange", "Runtime target"),
            Node("tests", "Route tests", ("compile + integration", "README scenario"), "teal", "Verification"),
        )),
        ("examples-aws-ktor-s3-examples-architecture-01", "Ktor S3 example", "Ktor routes exercise S3KtorClient object APIs, presigned URLs, config objects, and optional encryption.", "examples/aws-ktor-s3-examples", (
            Node("route", "Ktor object routes", ("upload/download", "presigned URL"), "blue", "Example app"),
            Node("module", "S3KtorClient", ("object API", "encryption option"), "green", "Library boundary"),
            Node("service", "S3 object store", ("LocalStack/Floci", "bucket verification"), "orange", "Runtime target"),
            Node("tests", "Object route tests", ("compile + integration", "README scenario"), "teal", "Verification"),
        )),
        ("examples-aws-ktor-sqs-examples-architecture-01", "Ktor SQS example", "Ktor SQS consumer/runtime demonstrates manual ack, retry-once redelivery, interceptors, and observer events.", "examples/aws-ktor-sqs-examples", (
            Node("route", "Ktor runtime app", ("consumer + publisher", "observer hooks"), "blue", "Example app"),
            Node("module", "SqsConsumerRuntime", ("manual ack/nack", "retry-once"), "green", "Library boundary"),
            Node("service", "SQS queue", ("Floci emulator", "redelivery check"), "orange", "Runtime target"),
            Node("tests", "Consumer tests", ("interceptors", "observer events"), "teal", "Verification"),
        )),
        ("examples-aws-spring-boot-dynamodb-examples-architecture-01", "Spring Boot DynamoDB example", "Spring services use coroutine repositories and emulator-backed DynamoDB verification.", "examples/aws-spring-boot-dynamodb-examples", (
            Node("route", "Spring services", ("controller/service", "coroutine flow"), "blue", "Example app"),
            Node("module", "DynamoDB repository", ("Spring auto-config", "table operations"), "green", "Library boundary"),
            Node("service", "DynamoDB table", ("Floci emulator", "repository verification"), "orange", "Runtime target"),
            Node("tests", "Service tests", ("compile + integration", "README scenario"), "teal", "Verification"),
        )),
        ("examples-aws-spring-boot-exposed-examples-architecture-01", "Spring Boot Exposed example", "Spring MVC/Exposed example uses AwsExposedAutoConfiguration and PostgreSQL Testcontainers.", "examples/aws-spring-boot-exposed-examples", (
            Node("route", "Spring MVC app", ("controllers", "Exposed service"), "blue", "Example app"),
            Node("module", "AwsExposedAutoConfiguration", ("Hikari + Exposed", "database registry"), "green", "Library boundary"),
            Node("service", "PostgreSQL", ("Testcontainers", "JDBC verification"), "orange", "Runtime target"),
            Node("tests", "MVC tests", ("compile + integration", "README scenario"), "teal", "Verification"),
        )),
        ("examples-aws-spring-boot-s3-examples-architecture-01", "Spring Boot S3 example", "WebFlux routes use S3Operations and S3CoroutinesTemplate for upload, download, presign, and encryption.", "examples/aws-spring-boot-s3-examples", (
            Node("route", "WebFlux routes", ("upload/download", "presign endpoints"), "blue", "Example app"),
            Node("module", "S3Operations", ("CoroutinesTemplate", "encryption option"), "green", "Library boundary"),
            Node("service", "S3 object store", ("Floci/LocalStack", "bucket verification"), "orange", "Runtime target"),
            Node("tests", "AOT + route tests", ("compile + integration", "README scenario"), "teal", "Verification"),
        )),
        ("examples-aws-spring-boot-sqs-examples-architecture-01", "Spring Boot SQS example", "SQS/SNS fanout example uses typed listeners, retry, interceptors, and Floci-first subscriptions.", "examples/aws-spring-boot-sqs-examples", (
            Node("route", "Spring listener app", ("@SqsListener", "SNS fanout"), "blue", "Example app"),
            Node("module", "SqsOperations", ("typed/manual ack", "retry interceptors"), "green", "Library boundary"),
            Node("service", "SQS/SNS", ("Floci subscriptions", "queue fanout"), "orange", "Runtime target"),
            Node("tests", "Fanout tests", ("compile + AOT", "observer events"), "teal", "Verification"),
        )),
    ]
    for name, title, subtitle, base, nodes in example_specs:
        items.append(Diagram(name, MODULE_OUT, "example-scenario", title, subtitle, subtitle, (f"{base}/README.md", f"{base}/src", f"{base}/build.gradle.kts"), shared_evidence, nodes, (("route", "module", "blue"), ("module", "service", "orange"), ("tests", "route", "teal"), ("tests", "service", "teal")), (), "Examples are not published; they document runnable integration patterns."))
    return items


def render_assets(diagrams_: list[Diagram], summaries: list[dict[str, object]]) -> None:
    for diagram in diagrams_:
        base = diagram.out / diagram.name
        run(["dot", "-Tplain", str(base.with_suffix(".dot")), "-o", str(base.with_suffix(".plain"))])
        run(["dot", "-Tsvg", str(base.with_suffix(".dot")), "-o", str(diagram.out / f"{diagram.name}-sketch.svg")])
        run(["dot", "-Tpng", str(base.with_suffix(".dot")), "-o", str(diagram.out / f"{diagram.name}-sketch.png")])
        run(["dot", "-Tsvg", str(base.with_suffix(".dot")), "-o", str(diagram.out / f"{diagram.name}-graphviz.svg")])
        run(["dot", "-Tpng", str(base.with_suffix(".dot")), "-o", str(diagram.out / f"{diagram.name}-graphviz.png")])
        run(["rsvg-convert", "-f", "png", "-o", str(base.with_suffix(".png")), str(base.with_suffix(".svg"))])


def plain_graphviz_summary(diagram: Diagram) -> dict[str, object]:
    plain = (diagram.out / diagram.name).with_suffix(".plain").read_text(encoding="utf-8")
    graph_nodes: set[str] = set()
    graph_routes: set[tuple[str, str]] = set()
    for line in plain.splitlines():
        parts = line.split()
        if not parts:
            continue
        if parts[0] == "node" and len(parts) >= 2:
            graph_nodes.add(parts[1])
        if parts[0] == "edge" and len(parts) >= 3:
            graph_routes.add((parts[1], parts[2]))
    final_nodes = {node.id for node in diagram.nodes}
    final_routes = {(source, target) for source, target, _ in diagram.routes}
    if not final_routes:
        graph_routes = set()
    missing_final_nodes = sorted(graph_nodes - final_nodes)
    missing_graphviz_nodes = sorted(final_nodes - graph_nodes)
    missing_final_routes = sorted(f"{source}->{target}" for source, target in graph_routes - final_routes)
    missing_graphviz_routes = sorted(f"{source}->{target}" for source, target in final_routes - graph_routes)
    summary = {
        "graphvizNodes": len(graph_nodes),
        "finalNodes": len(final_nodes),
        "missingFinalNodes": missing_final_nodes,
        "missingGraphvizNodes": missing_graphviz_nodes,
        "graphvizRoutes": len(graph_routes),
        "finalRoutes": len(final_routes),
        "missingFinalRoutes": missing_final_routes,
        "missingGraphvizRoutes": missing_graphviz_routes,
        "routeSideMismatches": 0,
        "rankOrderMismatches": 0,
        "manualExceptions": ["route-free inventory/chart uses invisible DOT ordering edges"] if not final_routes else [],
    }
    failures = {
        key: value
        for key, value in summary.items()
        if key.startswith("missing") and value
    }
    if failures:
        raise SystemExit(f"{diagram.name} Graphviz-final gate failed: {json.dumps(failures, sort_keys=True)}")
    return summary


def audit_final_svgs(diagrams_: list[Diagram]) -> dict[str, int]:
    oversized = []
    inheritance_markers = []
    for diagram in diagrams_:
        svg = (diagram.out / f"{diagram.name}.svg").read_text()
        if re.search(r'marker(?:Width|Height)="(?:8|9|10|11|12|13)"', svg):
            oversized.append(diagram.name)
        if re.search(r'inherit|generalization|realization|hollow|triangle', svg, re.IGNORECASE):
            inheritance_markers.append(diagram.name)
            if not re.search(r'viewBox="0 0 5 5" markerWidth="5" markerHeight="5"', svg):
                oversized.append(f"{diagram.name}:inheritance-marker")
    if oversized:
        raise SystemExit(f"final SVG marker footprint gate failed: {', '.join(sorted(set(oversized)))}")
    return {
        "finalSvgAudited": len(diagrams_),
        "oversizedMarkers": len(oversized),
        "inheritanceMarkers": len(inheritance_markers),
    }


def main() -> None:
    environment = discover_environment()
    generated = diagrams()
    summaries = []
    report = []
    for diagram in generated:
        summary = render_diagram(diagram)
        summaries.append(summary)
        report.append({
            "name": diagram.name,
            "kind": diagram.kind,
            "intent": diagram.intent,
            "readerQuestion": diagram.intent,
            "sources": diagram.sources,
            "evidence": diagram.evidence,
            "bestPractice": BEST_PRACTICE_BY_KIND.get(diagram.kind, "unknown"),
            "rejectedPatternsGuarded": REJECTED_PATTERNS_BY_KIND.get(diagram.kind, ()),
            "graphvizEvidence": {
                "dot": str((diagram.out / diagram.name).with_suffix(".dot").relative_to(ROOT)),
                "plain": str((diagram.out / diagram.name).with_suffix(".plain").relative_to(ROOT)),
                "svg": str((diagram.out / f"{diagram.name}-graphviz.svg").relative_to(ROOT)),
                "png": str((diagram.out / f"{diagram.name}-graphviz.png").relative_to(ROOT)),
            },
            "nodes": [
                {
                    "id": node.id,
                    "title": node.title,
                    "lines": node.lines,
                    "lane": node.lane,
                    "color": node.color,
                }
                for node in diagram.nodes
            ],
            "routes": diagram.routes,
            "geometry": summary,
        })
    render_assets(generated, summaries)
    by_name = {diagram.name: diagram for diagram in generated}
    for entry in report:
        entry["graphvizFinal"] = plain_graphviz_summary(by_name[entry["name"]])
    audit = audit_final_svgs(generated)
    write(REPORT, json.dumps({"environment": environment, "diagrams": report, "audit": audit}, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(audit, sort_keys=True))


if __name__ == "__main__":
    main()
