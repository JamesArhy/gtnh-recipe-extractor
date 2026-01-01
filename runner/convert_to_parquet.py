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


def _write_datapackage(out_dir: Path, root: dict) -> None:
    def resource(name: str, path: str, description: str, fields: list) -> dict:
        return {
            "name": name,
            "path": path,
            "profile": "tabular-data-resource",
            "format": "parquet",
            "mediatype": "application/x-parquet",
            "description": description,
            "schema": {"fields": fields},
        }

    resources = [
        resource(
            "recipe_maps",
            "recipe_maps.parquet",
            "One row per recipe source (GregTech RecipeMaps + non-GT providers) with machine identity and counts.",
            [
                {"name": "machine_id", "type": "string", "description": "Machine or recipe source ID."},
                {"name": "display_name", "type": "string", "description": "Friendly machine name."},
                {"name": "declaring_field", "type": "string", "description": "Declaring RecipeMaps field name."},
                {"name": "recipe_count", "type": "integer", "description": "Number of recipes in the map."},
            ],
        ),
        resource(
            "machine_index",
            "machine_index.parquet",
            "Machine index merged from recipe sources and MetaTileEntities, including bonuses when available.",
            [
                {"name": "machine_id", "type": "string", "description": "Machine or recipe source ID."},
                {"name": "display_name", "type": "string", "description": "Friendly machine name."},
                {"name": "declaring_field", "type": "string", "description": "Declaring RecipeMaps field name."},
                {"name": "recipe_count", "type": "integer", "description": "Number of recipes in the map."},
                {"name": "meta_tile_id", "type": "integer", "description": "GregTech MetaTileEntity ID."},
                {"name": "meta_tile_name", "type": "string", "description": "MetaTileEntity internal name."},
                {"name": "meta_tile_class", "type": "string", "description": "MetaTileEntity class name."},
                {"name": "parallel_bonus", "type": "number", "description": "Parallel bonus multiplier when available."},
                {"name": "max_parallel", "type": "number", "description": "Absolute max parallel operations when known; null when unknown."},
                {"name": "coil_bonus", "type": "number", "description": "Coil-derived speed or energy bonus."},
                {"name": "speed_bonus", "type": "number", "description": "Internal speed multiplier."},
                {"name": "efficiency_bonus", "type": "number", "description": "Efficiency multiplier."},
                {"name": "tooltip_derived", "type": "boolean", "description": "True if any bonus was sourced from the tooltip."},
            ],
        ),
        resource(
            "recipes",
            "recipes.parquet",
            "One row per recipe variant with power and duration metadata (non-GT values may be 0).",
            [
                {"name": "rid", "type": "string", "description": "Stable unique recipe ID."},
                {"name": "machine_id", "type": "string", "description": "Machine or recipe source ID for this recipe."},
                {"name": "recipe_class", "type": "string", "description": "Underlying Java class name."},
                {"name": "duration_ticks", "type": "integer", "description": "Recipe duration in ticks."},
                {"name": "eut", "type": "integer", "description": "EU per tick."},
                {"name": "chance_scale", "type": "integer", "description": "Chance scale for output chances."},
                {
                    "name": "output_chances_json",
                    "type": "string",
                    "description": "JSON array of output chance weights aligned to outputs.",
                },
            ],
        ),
        resource(
            "item_inputs",
            "item_inputs.parquet",
            "Normalized item inputs per recipe.",
            [
                {"name": "rid", "type": "string", "description": "Recipe ID."},
                {"name": "item_id", "type": "string", "description": "Item registry ID."},
                {"name": "count", "type": "integer", "description": "Item count per craft."},
                {"name": "meta", "type": "integer", "description": "Item metadata / damage value."},
                {"name": "display_name", "type": "string", "description": "Localized item name when available."},
                {"name": "unlocalized_name", "type": "string", "description": "Unlocalized item name when available."},
            ],
        ),
        resource(
            "item_outputs",
            "item_outputs.parquet",
            "Normalized item outputs per recipe.",
            [
                {"name": "rid", "type": "string", "description": "Recipe ID."},
                {"name": "item_id", "type": "string", "description": "Item registry ID."},
                {"name": "count", "type": "integer", "description": "Item count per craft."},
                {"name": "meta", "type": "integer", "description": "Item metadata / damage value."},
                {"name": "chance", "type": "number", "description": "Chance multiplier (0-1 typical) if available."},
                {"name": "display_name", "type": "string", "description": "Localized item name when available."},
                {"name": "unlocalized_name", "type": "string", "description": "Unlocalized item name when available."},
            ],
        ),
        resource(
            "fluid_inputs",
            "fluid_inputs.parquet",
            "Normalized fluid inputs per recipe.",
            [
                {"name": "rid", "type": "string", "description": "Recipe ID."},
                {"name": "fluid_id", "type": "string", "description": "Fluid ID."},
                {"name": "mb", "type": "integer", "description": "Fluid amount in millibuckets."},
                {"name": "is_gas", "type": "boolean", "description": "True if fluid is gaseous; null if unknown."},
                {"name": "display_name", "type": "string", "description": "Localized fluid name when available."},
                {"name": "unlocalized_name", "type": "string", "description": "Unlocalized fluid name when available."},
            ],
        ),
        resource(
            "fluid_outputs",
            "fluid_outputs.parquet",
            "Normalized fluid outputs per recipe.",
            [
                {"name": "rid", "type": "string", "description": "Recipe ID."},
                {"name": "fluid_id", "type": "string", "description": "Fluid ID."},
                {"name": "mb", "type": "integer", "description": "Fluid amount in millibuckets."},
                {"name": "is_gas", "type": "boolean", "description": "True if fluid is gaseous; null if unknown."},
                {"name": "display_name", "type": "string", "description": "Localized fluid name when available."},
                {"name": "unlocalized_name", "type": "string", "description": "Unlocalized fluid name when available."},
            ],
        ),
    ]

    package = {
        "name": "gtnh-recipe-extractor",
        "profile": "tabular-data-package",
        "title": "GTNH Recipe Extractor Parquet Outputs",
        "description": "Parquet outputs derived from GTNH RecipeMaps with schema annotations.",
        "created": root.get("generatedAt"),
        "resources": resources,
    }

    (out_dir / "datapackage.json").write_text(
        json.dumps(package, indent=2, sort_keys=False),
        encoding="utf-8",
    )


def main():
    raw_path = Path(os.environ.get("RAW_JSON_PATH", "/work/server/config/recipedumper/recipes.json"))
    machine_index_path = Path(
        os.environ.get(
            "MACHINE_INDEX_JSON_PATH",
            str(raw_path.with_name("machine_index.json")),
        )
    )
    out_dir = Path(os.environ.get("PARQUET_OUT_DIR", "/work/out/parquet"))

    if not raw_path.exists():
        raise SystemExit(f"Raw dump not found: {raw_path}")

    _ensure_dir(out_dir)

    with raw_path.open("r", encoding="utf-8") as f:
        root = json.load(f)

    machine_index_root = {}
    if machine_index_path.exists():
        with machine_index_path.open("r", encoding="utf-8") as f:
            machine_index_root = json.load(f)

    maps = root.get("recipeMaps", [])
    # ---- recipe_maps table ----
    map_rows = []
    machine_index_rows = []
    meta_index_rows = []
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
        machine_index_rows.append({
            "machine_id": machine_id,
            "display_name": m.get("displayName"),
            "declaring_field": m.get("declaringField"),
            "recipe_count": int(m.get("recipeCount") or 0),
            "parallel_bonus": m.get("parallelBonus"),
            "max_parallel": m.get("maxParallel"),
            "coil_bonus": m.get("coilBonus"),
            "speed_bonus": m.get("speedBonus"),
            "efficiency_bonus": m.get("efficiencyBonus"),
            "tooltip_derived": m.get("tooltipDerived"),
            "meta_tile_id": None,
            "meta_tile_name": None,
            "meta_tile_class": None,
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
                    "display_name": s.get("displayName"),
                    "unlocalized_name": s.get("unlocalizedName"),
                })

            for s in (r.get("itemOutputs") or []):
                out_item_rows.append({
                    "rid": rid,
                    "item_id": s.get("id"),
                    "count": int(s.get("count") or 0),
                    "meta": int(s.get("meta") or 0),
                    "chance": float(s.get("chance")) if s.get("chance") is not None else None,
                    "display_name": s.get("displayName"),
                    "unlocalized_name": s.get("unlocalizedName"),
                })

            for s in (r.get("fluidInputs") or []):
                in_fluid_rows.append({
                    "rid": rid,
                    "fluid_id": s.get("id"),
                    "mb": int(s.get("mb") or 0),
                    "is_gas": s.get("isGas"),
                    "display_name": s.get("displayName"),
                    "unlocalized_name": s.get("unlocalizedName"),
                })

            for s in (r.get("fluidOutputs") or []):
                out_fluid_rows.append({
                    "rid": rid,
                    "fluid_id": s.get("id"),
                    "mb": int(s.get("mb") or 0),
                    "is_gas": s.get("isGas"),
                    "display_name": s.get("displayName"),
                    "unlocalized_name": s.get("unlocalizedName"),
                })

    machine_index_list = []
    if isinstance(machine_index_root, list):
        machine_index_list = machine_index_root
    elif isinstance(machine_index_root, dict):
        machine_index_list = machine_index_root.get("machineIndex") or []

    for m in machine_index_list:
        meta_index_rows.append({
            "machine_id": m.get("machineId"),
            "display_name": m.get("displayName"),
            "meta_tile_id": m.get("metaTileId"),
            "meta_tile_name": m.get("metaTileName"),
            "meta_tile_class": m.get("metaTileClass"),
            "parallel_bonus": m.get("parallelBonus"),
            "max_parallel": m.get("maxParallel"),
            "coil_bonus": m.get("coilBonus"),
            "speed_bonus": m.get("speedBonus"),
            "efficiency_bonus": m.get("efficiencyBonus"),
            "tooltip_derived": m.get("tooltipDerived"),
        })

    machine_index_by_id = {r["machine_id"]: r for r in machine_index_rows if r.get("machine_id")}
    for meta in meta_index_rows:
        machine_id = meta.get("machine_id")
        if not machine_id:
            continue
        row = machine_index_by_id.get(machine_id)
        if row is None:
            row = {
                "machine_id": machine_id,
                "display_name": meta.get("display_name"),
                "declaring_field": None,
                "recipe_count": None,
                "parallel_bonus": None,
                "max_parallel": None,
                "coil_bonus": None,
                "speed_bonus": None,
                "efficiency_bonus": None,
                "tooltip_derived": None,
                "meta_tile_id": None,
                "meta_tile_name": None,
                "meta_tile_class": None,
            }
            machine_index_by_id[machine_id] = row

        if meta.get("display_name"):
            row["display_name"] = meta.get("display_name")
        for key in ("parallel_bonus", "max_parallel", "coil_bonus", "speed_bonus", "efficiency_bonus"):
            if row.get(key) is None and meta.get(key) is not None:
                row[key] = meta.get(key)
        if meta.get("tooltip_derived") is True:
            row["tooltip_derived"] = True
        for key in ("meta_tile_id", "meta_tile_name", "meta_tile_class"):
            if row.get(key) is None and meta.get(key) is not None:
                row[key] = meta.get(key)

    _write_parquet(pd.DataFrame(map_rows), out_dir / "recipe_maps.parquet")
    machine_index_df = pd.DataFrame(list(machine_index_by_id.values()))
    for col in ("parallel_bonus", "max_parallel", "coil_bonus", "speed_bonus", "efficiency_bonus", "meta_tile_id"):
        if col in machine_index_df.columns:
            machine_index_df[col] = pd.to_numeric(machine_index_df[col], errors="coerce")
    _write_parquet(machine_index_df, out_dir / "machine_index.parquet")
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
    _write_datapackage(out_dir, root)

    print(f"Parquet written to: {out_dir}")


if __name__ == "__main__":
    main()
