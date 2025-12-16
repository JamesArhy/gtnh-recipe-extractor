import json
import os
from pathlib import Path

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq


def _ensure_dir(p: Path) -> None:
    p.mkdir(parents=True, exist_ok=True)


def _write_parquet(df: pd.DataFrame, path: Path) -> None:
    table = pa.Table.from_pandas(df, preserve_index=False)
    pq.write_table(table, path, compression="zstd")


def main():
    raw_path = Path(os.environ.get("RAW_JSON_PATH", "/work/server/config/recipedumper/recipes.json"))
    out_dir = Path(os.environ.get("PARQUET_OUT_DIR", "/work/out/parquet"))

    if not raw_path.exists():
        raise SystemExit(f"Raw dump not found: {raw_path}")

    _ensure_dir(out_dir)

    with raw_path.open("r", encoding="utf-8") as f:
        root = json.load(f)

    maps = root.get("recipeMaps", [])
    # ---- recipe_maps table ----
    map_rows = []
    recipe_rows = []
    in_item_rows = []
    out_item_rows = []
    in_fluid_rows = []
    out_fluid_rows = []

    for m in maps:
        machine_id = m.get("machineId")
        map_rows.append({
            "machine_id": machine_id,
            "display_name": m.get("displayName"),
            "declaring_field": m.get("declaringField"),
            "recipe_count": int(m.get("recipeCount") or 0),
        })

        for r in (m.get("recipes") or []):
            rid = r.get("rid")
            recipe_rows.append({
                "rid": rid,
                "machine_id": machine_id,
                "recipe_class": r.get("recipeClass"),
                "duration_ticks": int(r.get("durationTicks") or 0),
                "eut": int(r.get("eut") or 0),
                "chance_scale": int(r["chanceScale"]) if r.get("chanceScale") is not None else None,
                # store raw list as JSON string for compactness; you can normalize later if you want
                "output_chances_json": json.dumps(r.get("outputChances")) if r.get("outputChances") is not None else None,
            })

            for s in (r.get("itemInputs") or []):
                in_item_rows.append({
                    "rid": rid,
                    "item_id": s.get("id"),
                    "count": int(s.get("count") or 0),
                    "meta": int(s.get("meta") or 0),
                })

            for s in (r.get("itemOutputs") or []):
                out_item_rows.append({
                    "rid": rid,
                    "item_id": s.get("id"),
                    "count": int(s.get("count") or 0),
                    "meta": int(s.get("meta") or 0),
                })

            for s in (r.get("fluidInputs") or []):
                in_fluid_rows.append({
                    "rid": rid,
                    "fluid_id": s.get("id"),
                    "mb": int(s.get("mb") or 0),
                })

            for s in (r.get("fluidOutputs") or []):
                out_fluid_rows.append({
                    "rid": rid,
                    "fluid_id": s.get("id"),
                    "mb": int(s.get("mb") or 0),
                })

    _write_parquet(pd.DataFrame(map_rows), out_dir / "recipe_maps.parquet")
    _write_parquet(pd.DataFrame(recipe_rows), out_dir / "recipes.parquet")
    _write_parquet(pd.DataFrame(in_item_rows), out_dir / "item_inputs.parquet")
    _write_parquet(pd.DataFrame(out_item_rows), out_dir / "item_outputs.parquet")
    _write_parquet(pd.DataFrame(in_fluid_rows), out_dir / "fluid_inputs.parquet")
    _write_parquet(pd.DataFrame(out_fluid_rows), out_dir / "fluid_outputs.parquet")

    # Optional: also write a tiny metadata file
    meta = {
        "generatedAt": root.get("generatedAt"),
        "minecraft": root.get("minecraft"),
        "mod": root.get("mod"),
        "maps": len(map_rows),
        "recipes": len(recipe_rows),
    }
    (out_dir / "_meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")

    print(f"Parquet written to: {out_dir}")


if __name__ == "__main__":
    main()
