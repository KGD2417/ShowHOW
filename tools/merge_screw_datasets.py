"""
Merge three Roboflow COCO exports into one dataset with a single class list.

The three disagree about names ("Hexagnol Bolt"), about granularity ("Hex
Socket Screw" vs "Hex Washer Screw") and about what is worth a class at all.
Left alone that produces a model with twelve near-duplicate labels and a
handful of examples of each, which is worse than six labels with real support.

Classes kept are the ones a laptop repair actually turns on: the driver itself,
and the screw heads that tell a learner which driver to reach for. Nuts, bolts
and washers are dropped -- they are the bulk of one dataset and none of them
appear inside a laptop.
"""
import os
import json
import shutil

ROOT = os.path.expanduser("~/sd_data")
OUT = os.path.expanduser("~/sd_merged")

# source name (lowercased) -> unified class, or None to drop
MAP = {
    "screwdriver": "screwdriver",
    "philips screw": "philips_screw",
    "pozidriv screw": "pozidriv_screw",
    "torx screw": "torx_screw",
    "hex washer screw": "hex_screw",
    "hex socket screw": "hex_screw",
    "square screw": "square_screw",
    # dropped: not found inside a laptop, and they dominate one dataset
    "hexagonal bolt": None,
    "hexagnol bolt": None,
    "hexagonal nut": None,
    "lock washer": None,
    # That dataset names its only real class "2", with supercategory
    # "Screwdriver". Every one of its 212 boxes is a screwdriver; the name is
    # just what someone typed. Dropping it cost the model the one class this
    # whole exercise is for.
    "2": "screwdriver",
}

CLASSES = ["screwdriver", "philips_screw", "pozidriv_screw",
           "torx_screw", "hex_screw", "square_screw"]

SOURCES = ["screwtypes", "screwtypes2", "screwdriver"]
# Roboflow's valid/test splits are small; fold test into train and keep valid.
SPLIT_MAP = {"train": "train", "test": "train", "valid": "valid"}


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)

    out = {}
    for s in ("train", "valid"):
        os.makedirs(os.path.join(OUT, s), exist_ok=True)
        out[s] = {
            "images": [], "annotations": [],
            "categories": [{"id": i + 1, "name": n, "supercategory": "none"}
                           for i, n in enumerate(CLASSES)],
        }
    cid = {n: i + 1 for i, n in enumerate(CLASSES)}
    next_img = {"train": 1, "valid": 1}
    next_ann = {"train": 1, "valid": 1}
    kept_per_class = {n: 0 for n in CLASSES}
    dropped = 0

    for src in SOURCES:
        for split in ("train", "valid", "test"):
            d = os.path.join(ROOT, src, split)
            jf = os.path.join(d, "_annotations.coco.json")
            if not os.path.isfile(jf):
                continue
            tgt = SPLIT_MAP[split]
            j = json.load(open(jf))
            cats = {c["id"]: c["name"].strip().lower() for c in j["categories"]}

            # which of this file's images survive (those with >=1 kept box)
            by_img = {}
            for a in j["annotations"]:
                unified = MAP.get(cats.get(a["category_id"], ""), "SKIP")
                if unified in (None, "SKIP"):
                    dropped += 1
                    continue
                by_img.setdefault(a["image_id"], []).append((a, unified))

            for im in j["images"]:
                boxes = by_img.get(im["id"])
                if not boxes:
                    continue
                new_name = f"{src}_{split}_{im['file_name']}"
                shutil.copyfile(os.path.join(d, im["file_name"]),
                                os.path.join(OUT, tgt, new_name))
                iid = next_img[tgt]
                next_img[tgt] += 1
                out[tgt]["images"].append({
                    "id": iid, "file_name": new_name,
                    "width": im["width"], "height": im["height"],
                })
                for a, unified in boxes:
                    out[tgt]["annotations"].append({
                        "id": next_ann[tgt], "image_id": iid,
                        "category_id": cid[unified], "bbox": a["bbox"],
                        "area": a.get("area", a["bbox"][2] * a["bbox"][3]),
                        "iscrowd": 0,
                    })
                    next_ann[tgt] += 1
                    kept_per_class[unified] += 1

    for s in ("train", "valid"):
        with open(os.path.join(OUT, s, "_annotations.coco.json"), "w") as f:
            json.dump(out[s], f)
        print(f"{s}: {len(out[s]['images'])} images, {len(out[s]['annotations'])} boxes")
    print("dropped boxes (unwanted classes):", dropped)
    print("kept per class:", kept_per_class)
    print("OUT:", OUT)


if __name__ == "__main__":
    main()
