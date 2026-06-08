#!/usr/bin/env python3
from __future__ import annotations

import html
import json
import math
import subprocess
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "assets" / "readme-diagrams"

FONT_TITLE = '"Architects Daughter","Comic Sans MS",cursive'
FONT_DETAIL = '"Comic Mono","SFMono-Regular",Menlo,monospace'


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


def e(value: str) -> str:
    return html.escape(value, quote=True)


def run(command: list[str]) -> None:
    subprocess.run(command, cwd=ROOT, check=True)


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def segment_intersects_rect(
    a: tuple[float, float],
    b: tuple[float, float],
    box: Box,
    clearance: float = 0.0,
) -> bool:
    left = box.left - clearance
    right = box.right + clearance
    top = box.top - clearance
    bottom = box.bottom + clearance
    x1, y1 = a
    x2, y2 = b
    if math.isclose(x1, x2):
        x = x1
        if left < x < right:
            return max(min(y1, y2), top) < min(max(y1, y2), bottom)
        return False
    if math.isclose(y1, y2):
        y = y1
        if top < y < bottom:
            return max(min(x1, x2), left) < min(max(x1, x2), right)
        return False
    return True


def endpoint_side(point: tuple[float, float], box: Box) -> str | None:
    x, y = point
    inside_y = box.top <= y <= box.bottom
    inside_x = box.left <= x <= box.right
    if math.isclose(x, box.left, abs_tol=0.01) and inside_y:
        return "left"
    if math.isclose(x, box.right, abs_tol=0.01) and inside_y:
        return "right"
    if math.isclose(y, box.top, abs_tol=0.01) and inside_x:
        return "top"
    if math.isclose(y, box.bottom, abs_tol=0.01) and inside_x:
        return "bottom"
    return None


def endpoint_bad(route: Route, boxes: dict[str, Box]) -> list[str]:
    errors: list[str] = []
    for index, box_id in ((0, route.source), (-1, route.target)):
        box = boxes[box_id]
        point = route.points[index]
        peer = route.points[1 if index == 0 else -2]
        side = endpoint_side(point, box)
        if side is None:
            errors.append(f"{route.id}:{box_id}:endpoint-not-on-boundary")
            continue
        horizontal = math.isclose(point[1], peer[1])
        vertical = math.isclose(point[0], peer[0])
        if side in {"left", "right"} and not horizontal:
            errors.append(f"{route.id}:{box_id}:side-{side}-not-horizontal")
        if side in {"top", "bottom"} and not vertical:
            errors.append(f"{route.id}:{box_id}:side-{side}-not-vertical")
    return errors


def validate_geometry(
    name: str,
    width: int,
    height: int,
    frame: Box,
    title_bottom: float,
    content_top: float,
    boxes: dict[str, Box],
    routes: list[Route],
    min_title_gap: float,
) -> dict[str, object]:
    bad_endpoint: list[str] = []
    bad_bends: list[str] = []
    interior_crossings: list[str] = []
    lane_clearance: list[str] = []
    segments = 0

    for route in routes:
        bad_endpoint.extend(endpoint_bad(route, boxes))
        for index, (a, b) in enumerate(zip(route.points, route.points[1:])):
            segments += 1
            horizontal = math.isclose(a[1], b[1])
            vertical = math.isclose(a[0], b[0])
            if not (horizontal or vertical):
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
    left_margin = content_left - frame.left
    right_margin = frame.right - content_right
    top_margin = content_top - frame.top
    bottom_margin = frame.bottom - content_bottom
    margin_imbalance = round(max(left_margin, right_margin, top_margin, bottom_margin) - min(left_margin, right_margin, top_margin, bottom_margin), 2)
    title_gap = round(content_top - title_bottom, 2)

    summary: dict[str, object] = {
        "diagram": name,
        "nodes": len(boxes),
        "routes": len(routes),
        "segments": segments,
        "badEndpointAngle": len(bad_endpoint),
        "badBends": len(bad_bends),
        "interiorCrossings": len(interior_crossings),
        "laneClearance": len(lane_clearance),
        "marginImbalance": margin_imbalance,
        "titleGap": title_gap,
        "canvas": f"{width}x{height}",
    }
    failures = {
        "badEndpointAngle": bad_endpoint,
        "badBends": bad_bends,
        "interiorCrossings": interior_crossings,
        "laneClearance": lane_clearance,
        "titleGap": [] if title_gap >= min_title_gap else [f"{title_gap} < {min_title_gap}"],
    }
    active_failures = {key: value for key, value in failures.items() if value}
    print(json.dumps(summary, sort_keys=True))
    if active_failures:
        raise SystemExit(f"{name} geometry gate failed: {json.dumps(active_failures, sort_keys=True)}")
    return summary


def svg_header(width: int, height: int, label: str) -> str:
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" aria-label="{e(label)}">
<defs>
  <filter id="shadow" x="-10%" y="-10%" width="120%" height="130%"><feDropShadow dx="0" dy="5" stdDeviation="5" flood-color="#AAB7C6" flood-opacity="0.20"/></filter>
</defs>
<style>
  svg{{background:#F5F7FA}}
  .frame{{fill:#FFFFFF;stroke:#D9E2EC;stroke-width:1.5}}
  .title{{font-family:{FONT_TITLE};font-size:34px;fill:#102033}}
  .subtitle,.detail,.footer-detail,.tiny{{font-family:{FONT_DETAIL};fill:#526274}}
  .subtitle{{font-size:14px}}
  .card-title{{font-family:{FONT_TITLE};font-size:20px;fill:#102033}}
  .detail{{font-size:13px}}
  .tiny{{font-size:10px}}
  .chip{{font-family:{FONT_DETAIL};font-size:10px;fill:#243447}}
  .card{{stroke-width:2;filter:url(#shadow)}}
  .edge{{fill:none;stroke-width:2.4;stroke-linecap:round;stroke-linejoin:round}}
  .edge-label{{font-family:{FONT_DETAIL};font-size:11px;fill:#344456}}
  .footer{{fill:#0E2238;stroke:#0E2238}}
  .footer-title{{font-family:{FONT_TITLE};font-size:16px;fill:#FFFFFF}}
  .footer-detail{{font-size:11px;fill:#DDE7F2}}
</style>
'''


def markers(colors: dict[str, str]) -> str:
    parts: list[str] = ["<defs>"]
    for name, color in colors.items():
        parts.append(
            f'  <marker id="arrow-{name}" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="strokeWidth"><path d="M 1 1 L 7 4 L 1 7 Z" fill="{color}"/></marker>'
        )
    parts.append("</defs>")
    return "\n".join(parts)


def line_path(points: tuple[tuple[float, float], ...]) -> str:
    first, *rest = points
    chunks = [f"M {first[0]:.1f} {first[1]:.1f}"]
    chunks.extend(f"L {x:.1f} {y:.1f}" for x, y in rest)
    return " ".join(chunks)


def card(box: Box, title: str, lines: list[str], fill: str, stroke: str) -> str:
    cy = box.cy
    block_height = 24 + len(lines) * 18
    start = cy - block_height / 2 + 18
    parts = [
        f'<g id="{e(box.id)}">',
        f'  <rect class="card" x="{box.x}" y="{box.y}" width="{box.w}" height="{box.h}" rx="10" fill="{fill}" stroke="{stroke}"/>',
        f'  <text class="card-title" x="{box.cx}" y="{start}" text-anchor="middle" dominant-baseline="middle">{e(title)}</text>',
    ]
    for index, line in enumerate(lines):
        parts.append(
            f'  <text class="detail" x="{box.cx}" y="{start + 24 + index * 18}" text-anchor="middle" dominant-baseline="middle">{e(line)}</text>'
        )
    parts.append("</g>")
    return "\n".join(parts)


def chip(x: int, y: int, width: int, text: str, fill: str, stroke: str) -> str:
    return (
        f'<g><rect x="{x}" y="{y}" width="{width}" height="24" rx="12" fill="{fill}" stroke="{stroke}"/>'
        f'<text class="chip" x="{x + width / 2}" y="{y + 12}" text-anchor="middle" dominant-baseline="middle">{e(text)}</text></g>'
    )


def generate_components() -> dict[str, object]:
    name = "bluetape4k-aws-components-04"
    width, height = 1500, 790
    frame = Box("frame", 26, 26, 1448, 738)
    boxes = {
        "java": Box("java", 80, 185, 290, 118),
        "kotlin": Box("kotlin", 125, 350, 290, 118),
        "exposed": Box("exposed", 80, 515, 335, 118),
        "spring": Box("spring", 590, 240, 350, 132),
        "ktor": Box("ktor", 560, 455, 350, 132),
        "examples": Box("examples", 1085, 350, 335, 132),
    }
    colors = {
        "core": "#3B82F6",
        "config": "#14B8A6",
        "verify": "#D6A441",
    }
    routes = [
        Route("java-spring", "java", "spring", ((370, 244), (485, 244), (485, 306), (590, 306)), colors["core"]),
        Route("java-ktor", "java", "ktor", ((370, 267), (455, 267), (455, 505), (560, 505)), colors["core"]),
        Route("kotlin-spring", "kotlin", "spring", ((415, 396), (515, 396), (515, 340), (590, 340)), colors["core"]),
        Route("kotlin-ktor", "kotlin", "ktor", ((415, 427), (505, 427), (505, 540), (560, 540)), colors["core"]),
        Route("exposed-spring", "exposed", "spring", ((415, 548), (480, 548), (480, 355), (590, 355)), colors["config"]),
        Route("exposed-ktor", "exposed", "ktor", ((415, 590), (515, 590), (515, 570), (560, 570)), colors["config"]),
        Route("spring-examples", "spring", "examples", ((940, 306), (1020, 306), (1020, 410), (1085, 410)), colors["verify"]),
        Route("ktor-examples", "ktor", "examples", ((910, 540), (1020, 540), (1020, 455), (1085, 455)), colors["verify"]),
    ]
    validate_geometry(name, width, height, frame, 105, 185, boxes, routes, 60)

    dot = '''digraph Bluetape4kAwsComponents {
  graph [rankdir=LR, bgcolor="#ffffff", pad=0.35, nodesep=0.8, ranksep=1.1, splines=ortho]
  node [shape=box, style="rounded,filled", fontname="Architects Daughter", fontsize=12, margin="0.14,0.09", color="#94a3b8", fillcolor="#f8fafc"]
  edge [fontname="Comic Mono", fontsize=10, penwidth=1.8, arrowsize=0.75]

  java [label="aws-java\\nJava SDK v2 wrappers\\nS3 Vectors opt-in", fillcolor="#E8F3FF", color="#5B8DEF"]
  kotlin [label="aws-kotlin\\nnative suspend clients\\nDSL helpers", fillcolor="#EAF7EF", color="#58A978"]
  exposed [label="aws-exposed\\ndatabase registry\\nRDS IAM and config", fillcolor="#E9F7F6", color="#45A7A1"]
  spring [label="aws-spring-boot\\nauto-configuration\\nS3 Vectors + messaging", fillcolor="#FFF3D9", color="#D6A441"]
  ktor [label="aws-ktor\\nSigV4 plugin and runtimes\\nS3 Vectors + queues", fillcolor="#F1ECFF", color="#8A72D6"]
  examples [label="examples\\nKtor + Spring Boot\\nFloci and LocalStack", fillcolor="#FDECEF", color="#DC6B82"]

  java -> spring [color="#3B82F6", xlabel="core wrappers"]
  java -> ktor [color="#3B82F6", xlabel="core wrappers"]
  kotlin -> spring [color="#3B82F6", xlabel="core wrappers"]
  kotlin -> ktor [color="#3B82F6", xlabel="core wrappers"]
  exposed -> spring [color="#14B8A6", xlabel="config bridge"]
  exposed -> ktor [color="#14B8A6", xlabel="config bridge"]
  spring -> examples [color="#D6A441", xlabel="validated by"]
  ktor -> examples [color="#D6A441", xlabel="validated by"]
}
'''
    write(OUT / f"{name}.dot", dot)

    svg = [
        svg_header(width, height, "AWS component map"),
        markers(colors),
        f'<rect x="{frame.x}" y="{frame.y}" width="{frame.w}" height="{frame.h}" rx="14" class="frame"/>',
        '<text class="title" x="54" y="72">AWS component map</text>',
        '<text class="subtitle" x="56" y="104">Core SDK wrappers feed framework modules and examples without forcing one application stack.</text>',
        chip(1095, 54, 120, "core flow", "#E8F3FF", colors["core"]),
        chip(1230, 54, 116, "config", "#E9F7F6", colors["config"]),
        chip(1360, 54, 86, "verify", "#FFF3D9", colors["verify"]),
        '<g id="routes">',
    ]
    marker_name = {value: key for key, value in colors.items()}
    for route in routes:
        key = marker_name[route.color]
        svg.append(
            f'<path class="edge" d="{line_path(route.points)}" stroke="{route.color}" marker-end="url(#arrow-{key})"/>'
        )
    svg.extend(
        [
            "</g>",
            card(boxes["java"], "aws-java", ["Java SDK v2 wrappers", "CompletableFuture.await", "S3 Vectors opt-in"], "#E8F3FF", "#5B8DEF"),
            card(boxes["kotlin"], "aws-kotlin", ["native suspend clients", "DSL helpers"], "#EAF7EF", "#58A978"),
            card(boxes["exposed"], "aws-exposed", ["database registry", "RDS IAM auth", "Secrets and Parameter paths"], "#E9F7F6", "#45A7A1"),
            card(boxes["spring"], "aws-spring-boot", ["auto-configuration", "S3 Vectors, SQS/SNS/KMS", "property sources"], "#FFF3D9", "#D6A441"),
            card(boxes["ktor"], "aws-ktor", ["SigV4 plugin", "S3 Vectors and SQS runtimes", "DynamoDB and Exposed adapters"], "#F1ECFF", "#8A72D6"),
            card(boxes["examples"], "examples", ["Ktor + Spring Boot", "Floci, LocalStack, PostgreSQL", "consumer scenario checks"], "#FDECEF", "#DC6B82"),
            '<rect class="footer" x="54" y="690" width="1392" height="54" rx="8"/>',
            '<text class="footer-title" x="72" y="713" dominant-baseline="middle">Component role</text>',
            '<text class="footer-detail" x="72" y="733" dominant-baseline="middle">Keep SDK wrappers small, place framework behavior in framework modules, verify with examples and emulator-backed scenarios.</text>',
            "</svg>",
        ]
    )
    write(OUT / f"{name}.svg", "\n".join(svg))
    return {"name": name}


def coverage_badge(x: float, y: float, status: str) -> str:
    if status == "yes":
        fill, stroke, label = "#EAF7EF", "#58A978", "yes"
    elif status == "opt":
        fill, stroke, label = "#FFF3D9", "#D6A441", "opt-in"
    else:
        fill, stroke, label = "#F4F7FA", "#8CA0B3", "-"
    width = 56 if status == "opt" else 46
    return (
        f'<rect x="{x - width / 2:.1f}" y="{y - 15:.1f}" width="{width}" height="30" rx="15" fill="{fill}" stroke="{stroke}"/>'
        f'<text class="detail" x="{x:.1f}" y="{y:.1f}" text-anchor="middle" dominant-baseline="middle">{label}</text>'
    )


def generate_coverage() -> dict[str, object]:
    name = "bluetape4k-aws-service-coverage-chart-05"
    width, height = 1900, 820
    frame = Box("frame", 26, 26, 1848, 768)
    chart_boxes = {
        "matrix": Box("matrix", 54, 145, 1792, 478),
        "latest": Box("latest", 54, 654, 1792, 58),
        "footer": Box("footer", 54, 722, 1792, 54),
    }
    validate_geometry(name, width, height, frame, 105, 145, chart_boxes, [], 34)

    services = [
        ("DynamoDB",),
        ("S3",),
        ("S3 Vectors",),
        ("SES/v2",),
        ("SNS",),
        ("SQS",),
        ("KMS",),
        ("CloudWatch", "+ Logs"),
        ("Kinesis",),
        ("STS",),
        ("RDS IAM",),
        ("Secrets", "Manager"),
        ("Parameter", "Store"),
    ]
    rows = [
        ("aws-java", "Core Java SDK v2 helpers", ["yes", "yes", "opt", "yes", "yes", "yes", "yes", "yes", "yes", "yes", "-", "-", "-"]),
        ("aws-kotlin", "Native suspend SDK helpers", ["yes", "yes", "-", "yes", "yes", "yes", "yes", "yes", "yes", "yes", "-", "-", "-"]),
        ("aws-exposed", "Database config and RDS IAM", ["-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "yes", "yes", "yes"]),
        ("aws-spring-boot", "Auto-config templates and property sources", ["yes", "yes", "opt", "yes", "yes", "yes", "yes", "yes", "-", "yes", "yes", "yes", "yes"]),
        ("aws-ktor", "Plugins, SigV4, S3/SQS/DynamoDB runtimes", ["yes", "yes", "opt", "-", "-", "yes", "yes", "yes", "-", "-", "yes", "yes", "yes"]),
        ("examples", "Runnable Spring Boot and Ktor scenarios", ["yes", "yes", "-", "-", "-", "yes", "yes", "-", "-", "-", "yes", "yes", "yes"]),
    ]

    dot = '''digraph G {
  graph [rankdir=TB, bgcolor="transparent", margin=0.12, pad=0.35, label="AWS service coverage", labelloc=t, fontname="Architects Daughter", fontsize=24]
  node [shape=plain, fontname="Comic Mono"]
  coverage [label="badge matrix: modules x services, including S3 Vectors opt-in"]
}
'''
    write(OUT / f"{name}.dot", dot)

    left, top = 54, 145
    table_w, table_h = 1792, 478
    module_w = 285
    col_w = (table_w - module_w - 28) / len(services)
    header_y = top + 34
    row_start = top + 84
    row_h = 58

    svg = [
        svg_header(width, height, "AWS service coverage chart"),
        f'<rect x="{frame.x}" y="{frame.y}" width="{frame.w}" height="{frame.h}" rx="14" class="frame"/>',
        '<text class="title" x="54" y="72">AWS service coverage</text>',
        '<text class="subtitle" x="56" y="104">The chart reflects current source modules, optional SDK dependencies, and runnable example scenarios.</text>',
        chip(1520, 58, 86, "covered", "#EAF7EF", "#58A978"),
        chip(1620, 58, 86, "opt-in", "#FFF3D9", "#D6A441"),
        chip(1720, 58, 98, "not scoped", "#F4F7FA", "#8CA0B3"),
        f'<rect x="{left}" y="{top}" width="{table_w}" height="{table_h}" rx="10" fill="#FFFFFF" stroke="#D9E2EC"/>',
    ]
    for i, label_lines in enumerate(services):
        cx = left + module_w + 28 + col_w * i + col_w / 2
        for j, label in enumerate(label_lines):
            svg.append(f'<text class="card-title" x="{cx:.1f}" y="{header_y + j * 18:.1f}" text-anchor="middle">{e(label)}</text>')
        xline = left + module_w + 28 + col_w * i
        svg.append(f'<line x1="{xline:.1f}" y1="{top + 22}" x2="{xline:.1f}" y2="{top + table_h - 24}" stroke="#E2E8F0"/>')
    for row_index, (module, desc, statuses) in enumerate(rows):
        y = row_start + row_h * row_index
        svg.append(f'<line x1="{left + 20}" y1="{y - 20:.1f}" x2="{left + table_w - 20}" y2="{y - 20:.1f}" stroke="#E2E8F0"/>')
        svg.append(f'<text class="card-title" x="{left + 24}" y="{y:.1f}">{e(module)}</text>')
        svg.append(f'<text class="tiny" x="{left + 24}" y="{y + 20:.1f}">{e(desc)}</text>')
        for col_index, status in enumerate(statuses):
            cx = left + module_w + 28 + col_w * col_index + col_w / 2
            svg.append(coverage_badge(cx, y - 5, status))
    svg.extend(
        [
            '<rect x="54" y="654" width="1792" height="58" rx="8" fill="#FFFFFF" stroke="#D9E2EC"/>',
            '<text class="card-title" x="78" y="679">Latest additions</text>',
            '<text class="detail" x="78" y="700">S3 Vectors is opt-in for Java, Spring Boot, and Ktor while remaining a consumer-provided SDK dependency.</text>',
            '<rect class="footer" x="54" y="722" width="1792" height="54" rx="8"/>',
            '<text class="footer-title" x="72" y="745" dominant-baseline="middle">Coverage role</text>',
            '<text class="footer-detail" x="72" y="765" dominant-baseline="middle">Core SDK modules cover broad service wrappers; framework modules expose the services they configure or operate directly.</text>',
            "</svg>",
        ]
    )
    write(OUT / f"{name}.svg", "\n".join(svg))
    return {"name": name}


def render_assets(names: list[str]) -> None:
    for name in names:
        base = OUT / name
        run(["dot", "-Tplain", str(base.with_suffix(".dot")), "-o", str(base.with_suffix(".plain"))])
        run(["dot", "-Tsvg", str(base.with_suffix(".dot")), "-o", str(OUT / f"{name}-sketch.svg")])
        run(["dot", "-Tpng", str(base.with_suffix(".dot")), "-o", str(OUT / f"{name}-sketch.png")])
        run(["rsvg-convert", "-f", "png", "-o", str(base.with_suffix(".png")), str(base.with_suffix(".svg"))])


def main() -> None:
    generated = [generate_components()["name"], generate_coverage()["name"]]
    render_assets(generated)


if __name__ == "__main__":
    main()
