import os
import json
from roboflow import Roboflow

key = open("os.path.expanduser("~/.roboflow_key")").read().strip()
rf = Roboflow(api_key=key)
root = os.path.expanduser("~/sd_data")
os.makedirs(root, exist_ok=True)

TARGETS = [
    ("rf100-vl", "screwdetectclassification-xrrbi-hkwlh-lybq", 1, "screwtypes"),
    ("corrosion-a1pkl", "screw-detection-classification", 1, "screwtypes2"),
    ("project-yrep1", "screwdriver-ohmov", 6, "screwdriver"),
]

for ws, proj, ver, name in TARGETS:
    loc = os.path.join(root, name)
    try:
        rf.workspace(ws).project(proj).version(ver).download(
            "coco", location=loc, overwrite=True
        )
    except Exception as e:
        print("FAIL", name, type(e).__name__, str(e)[:120])
        continue

    reported = False
    for split in ("train", "valid", "test"):
        p = os.path.join(loc, split, "_annotations.coco.json")
        if os.path.isfile(p):
            j = json.load(open(p))
            names = [c["name"] for c in j.get("categories", [])]
            print("OK", name, split, len(j.get("images", [])), "imgs", names)
            reported = True
    if not reported:
        print("OK", name, "downloaded, contents:", os.listdir(loc)[:8])
