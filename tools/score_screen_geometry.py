"""Measure a declared calibration chart in a raster screen capture, offline.

Pixel-edge coordinates are used throughout. Rotation is clockwise; horizontal
mirroring happens after rotation. A fitted map describes static framing only,
never transmitted resolution, sharpness, physical pixels or screen timing.
"""

import argparse
import hashlib
import json
import math
from pathlib import Path

from benchmark_layout import original, output_path, resolve as resolve_input
from evidence_relocation import logical

import cv2
import numpy as np
from PIL import Image


DETECTOR = "unique-color-patches-v1"
VALIDATION_CHECKS = {"fit", "fill", "stretch", "rotation", "mirror", "bars", "missing", "ambiguous"}
TOLERANCE_FIELDS = {"landmark_px", "scale_fraction", "stretch_fraction", "bars_px", "crop_px", "orientation_degrees"}


def _number(value, name, minimum=0):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < minimum:
        raise ValueError(f"{name} must be finite and >= {minimum}")
    return float(value)


def _integer(value, name, minimum=0):
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ValueError(f"{name} must be an integer >= {minimum}")
    return value


def _file(entry, base, evidence, name):
    if not isinstance(entry, dict) or not entry.get("path") or not entry.get("sha256"):
        raise ValueError(f"missing hashed {name}")
    path = resolve_input(entry["path"], base=base)
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    digest = hasher.hexdigest()
    if digest != str(entry["sha256"]).lower():
        raise ValueError(f"{name} SHA256 mismatch")
    evidence[name] = {"path": str(logical(path)), "sha256": digest}
    return path


def _rectangle(bounds, width, height, name):
    if not isinstance(bounds, list) or len(bounds) != 4:
        raise ValueError(f"{name} must be [x,y,width,height]")
    x, y, w, h = [_integer(v, name, 1 if i >= 2 else 0) for i, v in enumerate(bounds)]
    if x + w > width or y + h > height:
        raise ValueError(f"{name} exceeds image")
    return x, y, w, h


def _rgb(value, name):
    if not isinstance(value, list) or len(value) != 3:
        raise ValueError(f"{name} RGB must have three integer values")
    result = [_integer(v, name) for v in value]
    if max(result) > 255:
        raise ValueError(f"{name} RGB exceeds 255")
    return np.array(result, dtype=np.int16)


def _components(image, rgb, tolerance, minimum_area):
    mask = np.all(np.abs(image.astype(np.int16) - rgb) <= tolerance, axis=2).astype(np.uint8)
    count, labels, stats, centroids = cv2.connectedComponentsWithStats(mask, connectivity=8)
    result = []
    for index in range(1, count):
        x, y, w, h, area = (int(v) for v in stats[index])
        # A calibration patch must have a 3x3 core. One-pixel interpolation
        # fringes can equal another target color, but are not patch detections.
        if area >= minimum_area and w >= 3 and h >= 3:
            result.append({"bounds": [x, y, w, h], "area": area,
                           "center": (centroids[index] + 0.5).tolist()})
    return result


def _orient(points, width, height, rotation, mirror):
    if rotation == 0:
        matrix, offset, size = np.eye(2), [0, 0], [width, height]
    elif rotation == 90:
        matrix, offset, size = np.array([[0, -1], [1, 0]]), [height, 0], [height, width]
    elif rotation == 180:
        matrix, offset, size = -np.eye(2), [width, height], [width, height]
    else:
        matrix, offset, size = np.array([[0, 1], [-1, 0]]), [0, width], [height, width]
    if mirror:
        flip = np.array([[-1, 0], [0, 1]])
        matrix, offset = flip @ matrix, flip @ np.array(offset) + [size[0], 0]
    return np.asarray(points) @ matrix.T + offset, matrix, np.array(offset), size


def _mapping(width, height, viewport, mode, rotation, mirror):
    _, orientation, offset, size = _orient([[0, 0]], width, height, rotation, mirror)
    x, y, w, h = viewport
    scale = (min if mode == "FIT" else max)(w / size[0], h / size[1])
    translation = np.array([x + (w - size[0] * scale) / 2, y + (h - size[1] * scale) / 2])
    return scale * orientation, scale * offset + translation, scale, size


def _bounds(points):
    return np.array([*np.min(points, axis=0), *np.max(points, axis=0)])


def _bars_crop(bounds, viewport):
    x, y, w, h = viewport
    inside = np.array([bounds[0] - x, bounds[1] - y, x + w - bounds[2], y + h - bounds[3]])
    return np.maximum(inside, 0), np.maximum(-inside, 0)


def _validate_chart(spec, reference):
    height, width = reference.shape[:2]
    if spec.get("pixel_aspect") != [1, 1]:
        raise ValueError("calibration must declare square pixels as pixel_aspect [1,1]")
    for name in ("border", "square", "circle"):
        if not isinstance(spec.get(name), dict):
            raise ValueError(f"calibration requires declared {name} geometry")
    border = spec.get("border", {})
    thickness = _integer(border.get("thickness"), "border thickness", 1)
    if thickness * 2 >= min(width, height):
        raise ValueError("invalid calibration border")
    border_rgb = _rgb(border.get("rgb"), "border")
    border_mask = np.zeros((height, width), dtype=bool)
    border_mask[:thickness] = border_mask[-thickness:] = True
    border_mask[:, :thickness] = border_mask[:, -thickness:] = True
    if not np.all(reference[border_mask] == border_rgb):
        raise ValueError("reference border does not match calibration")
    landmarks = spec.get("landmarks")
    if not isinstance(landmarks, list) or len(landmarks) < 4:
        raise ValueError("at least four distinct calibration landmarks are required")
    targets, ids = [], set()
    for landmark in landmarks:
        if not isinstance(landmark, dict):
            raise ValueError("each calibration landmark must be an object")
        name = landmark.get("id")
        if not isinstance(name, str) or not name or name in ids or name in {"__circle__", "__square__"}:
            raise ValueError("calibration landmark IDs must be unique")
        ids.add(name)
        x, y, w, h = _rectangle(landmark.get("bounds"), width, height, name)
        rgb = _rgb(landmark.get("rgb"), name)
        if not np.all(reference[y:y+h, x:x+w] == rgb):
            raise ValueError(f"reference patch {name} does not match calibration")
        targets.append({"id": name, "kind": "landmark", "bounds": [x, y, w, h], "rgb": rgb,
                        "center": [x + w / 2, y + h / 2]})
    square = spec.get("square", {})
    x, y, w, h = _rectangle(square.get("bounds"), width, height, "square")
    rgb = _rgb(square.get("rgb"), "square")
    if w != h or not np.all(reference[y:y+h, x:x+w] == rgb):
        raise ValueError("reference square does not match calibration")
    targets.append({"id": "__square__", "kind": "square", "bounds": [x, y, w, h], "rgb": rgb,
                    "center": [x + w/2, y + h/2]})
    circle = spec.get("circle", {})
    center = circle.get("center")
    if not isinstance(center, list) or len(center) != 2:
        raise ValueError("circle requires native center")
    cx, cy = [_number(v, "circle center") for v in center]
    radius = _integer(circle.get("radius"), "circle radius", 2)
    x, y = int(cx - radius), int(cy - radius)
    if cx != x + radius or cy != y + radius:
        raise ValueError("circle center must use integer edge coordinates")
    _rectangle([x, y, 2*radius, 2*radius], width, height, "circle")
    rgb = _rgb(circle.get("rgb"), "circle")
    yy, xx = np.mgrid[y:y+2*radius, x:x+2*radius]
    expected_mask = (xx + 0.5 - cx)**2 + (yy + 0.5 - cy)**2 <= radius**2
    actual_mask = np.all(reference[y:y+2*radius, x:x+2*radius] == rgb, axis=2)
    if not np.array_equal(expected_mask, actual_mask):
        raise ValueError("reference circle does not match the declared filled disc")
    targets.append({"id": "__circle__", "kind": "circle", "bounds": [x, y, 2*radius, 2*radius],
                    "rgb": rgb, "center": [cx, cy]})
    return targets


def analyze(spec, base):
    """Return reproducible static geometry, or explicit UNMEASURED prerequisites.

    Required spec keys: calibration, capture, viewport, mapping, detector,
    tolerances and validation. Calibration contains a hashed reference plus
    native width/height/pixel_aspect/border/landmarks/circle/square. Capture is
    a hashed raster image. RGB24 references also declare file_frame (default 0).
    """
    result = {"schema": "benchmark-screen-geometry-v1", "measurement_status": "UNMEASURED",
              "scope": "static raster capture geometry; no encoded resolution, detail or physical timing proof",
              "detector": DETECTOR, "evidence": {}, "measurements": [], "limitations": [
                  "Screen capture dimensions are not transmitted resolution or resolved detail.",
                  "Each visible target needs a connected color core at least 3x3 pixels and minimum_area pixels.",
                  "Photographed screens and compressed-camera captures require separate detector validation.",
                  "Affine framing requires the chart; arbitrary natural-scene matching is not accepted."]}

    def measure(name, value, passed=None, reason=""):
        row = {"name": name, "family": "screen_geometry", "status": "UNMEASURED" if passed is None else "PASS" if passed else "FAIL",
               "value": value, "reason": reason}
        result["measurements"].append(row)

    try:
        if not isinstance(spec, dict):
            raise ValueError("screen geometry spec must be an object")
        for name in ("calibration", "mapping", "detector", "tolerances"):
            if not isinstance(spec.get(name), dict):
                raise ValueError(f"missing or invalid {name} object")
        base = Path(base)
        calibration = spec.get("calibration", {})
        width = _integer(calibration.get("width"), "calibration width", 1)
        height = _integer(calibration.get("height"), "calibration height", 1)
        reference_spec = calibration.get("reference", {})
        reference_path = _file(reference_spec, base, result["evidence"], "reference")
        if reference_spec.get("format", "image") == "RGB24":
            frame = _integer(reference_spec.get("file_frame", 0), "reference file_frame")
            frame_bytes = width * height * 3
            size = reference_path.stat().st_size
            if size % frame_bytes or (frame+1)*frame_bytes > size:
                raise ValueError("RGB24 reference has incomplete or missing frame")
            with reference_path.open("rb") as stream:
                stream.seek(frame * frame_bytes)
                reference = np.frombuffer(stream.read(frame_bytes), dtype=np.uint8).reshape(height, width, 3)
        elif reference_spec.get("format", "image") == "image":
            with Image.open(reference_path) as image:
                reference = np.array(image.convert("RGB"))
            if reference.shape[:2] != (height, width):
                raise ValueError("reference dimensions disagree with native calibration")
        else:
            raise ValueError("unsupported calibration reference format")
        targets = _validate_chart(calibration, reference)
        capture_path = _file(spec.get("capture"), base, result["evidence"], "capture")
        with Image.open(capture_path) as image:
            capture = np.array(image.convert("RGB"))
        viewport = _rectangle(spec.get("viewport"), capture.shape[1], capture.shape[0], "viewport")
        mapping = spec.get("mapping", {})
        rotation, mirror, mode = mapping.get("rotation"), mapping.get("mirror"), mapping.get("mode")
        if type(rotation) is not int or rotation not in (0, 90, 180, 270) or type(mirror) is not bool or mode not in ("FIT", "FILL"):
            raise ValueError("mapping requires FIT/FILL, clockwise rotation 0/90/180/270 and boolean mirror")
        detector = spec.get("detector", {})
        color_tolerance = _integer(detector.get("color_tolerance"), "color_tolerance")
        minimum_area = _integer(detector.get("minimum_area"), "minimum_area", 9)
        if color_tolerance > 64:
            raise ValueError("color_tolerance exceeds the detector's validated 0..64 domain")
        tolerances = spec.get("tolerances", {})
        if set(tolerances) != TOLERANCE_FIELDS:
            raise ValueError("missing or unknown predeclared geometry tolerances")
        tolerances = {k: _number(v, k) for k, v in tolerances.items()}
        for i, target in enumerate(targets):
            for other in targets[:i]:
                if np.max(np.abs(target["rgb"] - other["rgb"])) <= color_tolerance * 2:
                    raise ValueError("calibration target colors are ambiguous at the declared tolerance")
            native = _components(reference, target["rgb"], color_tolerance, minimum_area)
            if len(native) != 1 or native[0]["bounds"] != target["bounds"]:
                raise ValueError(f"native target {target['id']} is ambiguous or too small")
        expected_matrix, expected_translation, scale, oriented_size = _mapping(width, height, viewport, mode, rotation, mirror)
        expected_orientation = _orient([[0, 0]], width, height, rotation, mirror)[1]
        x, y, w, h = viewport
        crop = capture[y:y+h, x:x+w]
        observations, source_points, output_points = [], [], []
        result["observations"] = observations
        for target in targets:
            tx, ty, tw, th = target["bounds"]
            corners = np.array([[tx, ty], [tx+tw, ty], [tx, ty+th], [tx+tw, ty+th]])
            expected_bounds = _bounds(corners @ expected_matrix.T + expected_translation)
            fully_visible = bool(expected_bounds[0] >= x and expected_bounds[1] >= y and expected_bounds[2] <= x+w and expected_bounds[3] <= y+h)
            wholly_outside = bool(expected_bounds[2] <= x or expected_bounds[3] <= y or expected_bounds[0] >= x+w or expected_bounds[1] >= y+h)
            components = _components(crop, target["rgb"], color_tolerance, minimum_area)
            observation = {"id": target["id"], "kind": target["kind"], "expected_visibility": "VISIBLE" if fully_visible else "OUTSIDE" if wholly_outside else "CROPPED",
                           "expected_bounds": expected_bounds.tolist(), "components": components}
            observations.append(observation)
            if len(components) > 1:
                raise ValueError(f"ambiguous captured target {target['id']}")
            if not components:
                if fully_visible:
                    raise ValueError(f"missing or occluded captured target {target['id']}")
                continue
            detected = components[0]
            bx, by, bw, bh = detected["bounds"]
            clipped = bx == 0 or by == 0 or bx+bw == w or by+bh == h
            observation["clipped"] = clipped
            if fully_visible and clipped:
                raise ValueError(f"unexpectedly clipped captured target {target['id']}")
            if fully_visible and not clipped:
                source_points.append(target["center"])
                output_points.append(np.array(detected["center"]) + [x, y])
        source_points, output_points = np.array(source_points), np.array(output_points)
        if len(source_points) < 4:
            raise ValueError("fewer than four unoccluded calibration points remain visible")
        design = np.column_stack([source_points, np.ones(len(source_points))])
        if np.linalg.matrix_rank(design) != 3:
            raise ValueError("visible calibration points cannot resolve a 2D mapping")
        fit = np.linalg.lstsq(design, output_points, rcond=None)[0]
        matrix, translation = fit[:2].T, fit[2]
        residual = np.linalg.norm(design @ fit - output_points, axis=1)
        expected_error = np.linalg.norm(source_points @ expected_matrix.T + expected_translation - output_points, axis=1)
        oriented_matrix = matrix @ expected_orientation.T
        scales = np.linalg.norm(oriented_matrix, axis=0)
        if min(scales) <= 0:
            raise ValueError("degenerate detected mapping")
        unit = matrix / np.linalg.norm(matrix, axis=0)
        candidates = []
        for actual_rotation in (0, 90, 180, 270):
            for actual_mirror in (False, True):
                orientation = _orient([[0, 0]], width, height, actual_rotation, actual_mirror)[1]
                candidates.append((float(np.linalg.norm(unit-orientation)), actual_rotation, actual_mirror, orientation))
        _, actual_rotation, actual_mirror, orientation = min(candidates, key=lambda entry: entry[0])
        angular_error = max(math.degrees(math.acos(float(np.clip(np.dot(unit[:, i], orientation[:, i]), -1, 1)))) for i in (0, 1))
        corners = np.array([[0, 0], [width, 0], [0, height], [width, height]])
        actual_bounds = _bounds(corners @ matrix.T + translation)
        expected_bounds = _bounds(corners @ expected_matrix.T + expected_translation)
        actual_bars, actual_crop = _bars_crop(actual_bounds, viewport)
        expected_bars, expected_crop = _bars_crop(expected_bounds, viewport)
        result["mapping"] = {"requested": mapping, "observed_rotation": actual_rotation, "observed_mirror": actual_mirror,
                             "affine": [*matrix.tolist(), translation.tolist()], "scale_x": float(scales[0]), "scale_y": float(scales[1]),
                             "expected_uniform_scale": scale, "content_bounds": actual_bounds.tolist(), "expected_content_bounds": expected_bounds.tolist(),
                             "bars_px": actual_bars.tolist(), "expected_bars_px": expected_bars.tolist(), "crop_screen_px": actual_crop.tolist(),
                             "expected_crop_screen_px": expected_crop.tolist(), "edge_order": ["left", "top", "right", "bottom"]}
        result["tolerances"] = tolerances
        validated = False
        try:
            validation_path = _file(spec.get("validation"), base, result["evidence"], "validation")
            validation = json.loads(validation_path.read_text(encoding="utf-8"))
            if not isinstance(validation, dict) or not isinstance(validation.get("checks"), dict):
                raise ValueError("validation receipt must be an object with test checks")
            validated = (validation.get("schema") == "benchmark-screen-geometry-validation-v1" and validation.get("detector") == DETECTOR
                         and validation.get("scope") == "raster-screen-capture" and validation.get("reference_sha256") == result["evidence"]["reference"]["sha256"]
                         and validation.get("tolerances") == tolerances and validation.get("detector_settings") == detector
                         and all(validation.get("checks", {}).get(name) is True for name in VALIDATION_CHECKS))
            if not validated:
                result["validation_reason"] = "detector validation scope/reference/settings/tolerances or checks do not match"
        except (ValueError, OSError, TypeError) as error:
            result["validation_reason"] = str(error)

        def gate(name, value, passed):
            measure(name, value, passed if validated else None, "" if validated else result.get("validation_reason", "missing detector validation"))

        gate("screen_landmark_error_px", float(max(expected_error)), bool(max(expected_error) <= tolerances["landmark_px"]))
        gate("screen_affine_residual_px", float(max(residual)), bool(max(residual) <= tolerances["landmark_px"]))
        scale_error = float(max(abs(scales / scale - 1)))
        gate("screen_scale_error_fraction", scale_error, scale_error <= tolerances["scale_fraction"])
        stretch = float(max(scales) / min(scales) - 1)
        gate("screen_stretch_fraction", stretch, stretch <= tolerances["stretch_fraction"])
        gate("screen_orientation", {"rotation": actual_rotation, "mirror": actual_mirror, "off_axis_degrees": angular_error},
             actual_rotation == rotation and actual_mirror == mirror and angular_error <= tolerances["orientation_degrees"])
        bars_error = float(max(abs(actual_bars - expected_bars)))
        gate("screen_bars_error_px", bars_error, bars_error <= tolerances["bars_px"])
        crop_error = float(max(abs(actual_crop - expected_crop)))
        gate("screen_crop_error_px", crop_error, crop_error <= tolerances["crop_px"])
        shape_errors = []
        for observation in observations:
            if observation["kind"] not in ("circle", "square") or observation["expected_visibility"] != "VISIBLE":
                continue
            bx, by, bw, bh = observation["components"][0]["bounds"]
            observed_bounds = np.array([x+bx, y+by, x+bx+bw, y+by+bh])
            shape_errors.append(float(max(abs(observed_bounds - observation["expected_bounds"]))))
        if len(shape_errors) != 2:
            measure("screen_shape_bounds_error_px", None, reason="circle and square are not both fully visible; use a calibration layout suited to this crop")
        else:
            gate("screen_shape_bounds_error_px", max(shape_errors), max(shape_errors) <= tolerances["landmark_px"])
        result["measurement_status"] = "MEASURED" if validated else "UNMEASURED"
        result["verdict"] = ("FAIL" if any(row["status"] == "FAIL" for row in result["measurements"])
                             else "UNMEASURED" if any(row["status"] == "UNMEASURED" for row in result["measurements"]) else "PASS")
    except (ValueError, OSError, TypeError, KeyError) as error:
        measure("screen_geometry_prerequisites", None, reason=str(error))
        result["verdict"] = "UNMEASURED"
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    args.output = output_path(args.output, "runs")
    try:
        if args.output.exists():
            raise ValueError("output already exists; use a new run-owned result")
        spec_path = resolve_input(args.spec)
        spec = json.loads(spec_path.read_text(encoding="utf-8"))
        result = analyze(spec, original(spec_path).parent)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print(f"{result['verdict']}: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
