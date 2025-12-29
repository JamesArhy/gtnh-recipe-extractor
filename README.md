
# GTNH Recipe Extractor

### GregTech RecipeMaps → Structured Parquet (Docker-only)

This repository extracts **GregTech (GT5-Unofficial) RecipeMaps** and selected **non-GT recipe providers**
from **GT New Horizons (GTNH)** by:

1. **Building a Forge 1.7.10 dumper mod inside Docker** (no local Java or Gradle)
2. **Launching a headless GTNH server inside Docker**
3. Reflecting **GregTech RecipeMaps** plus **vanilla crafting/smelting** and **Railcraft** recipe registries at runtime
4. Converting the runtime dump into **efficient, structured Parquet datasets**

The output is designed for:

* graph databases (Kùzu, Neo4j, etc.)
* Dash Cytoscape / Cytoscape.js
* recipe dependency analysis
* throughput & rate modeling (items/sec, mB/sec)

---

## Output Overview

After a successful run, artifacts are written to:

```
out/parquet/
```

### Parquet Tables

| File                    | Description                                                |
| ----------------------- | ---------------------------------------------------------- |
| `recipe_maps.parquet`   | One row per recipe source (GT RecipeMaps + non-GT)         |
| `machine_index.parquet` | Friendly machine index with bonus fields                  |
| `recipes.parquet`       | One row per recipe variant (EU/t, duration, etc.)          |
| `edges.parquet`         | **Unified graph edges** (inputs & outputs, items & fluids) |
| `item_inputs.parquet`   | Item inputs (normalized)                                   |
| `item_outputs.parquet`  | Item outputs (normalized)                                  |
| `fluid_inputs.parquet`  | Fluid inputs (normalized)                                  |
| `fluid_outputs.parquet` | Fluid outputs (normalized)                                 |
| `datapackage.json`      | Frictionless Data schema + column annotations             |
| `_meta.json`            | Small metadata summary                                     |

---

## `edges.parquet` (Graph-Friendly Schema)

Each row represents a single input or output edge attached to a recipe.

| Column       | Meaning                                              |
| ------------ | ---------------------------------------------------- |
| `rid`        | Recipe ID                                            |
| `machine_id` | Machine / RecipeMap ID                               |
| `direction`  | `"in"` or `"out"`                                    |
| `kind`       | `"item"` or `"fluid"`                                |
| `node_id`    | `"item:modid:name"` or `"fluid:water"`               |
| `qty`        | Item count or fluid mB per craft                     |
| `unit`       | `"items"` or `"mB"`                                  |
| `meta`       | Item metadata (nullable)                             |
| `chance`     | Output chance (nullable; raw GT semantics preserved) |

This table alone is sufficient to construct a full recipe dependency graph.

---

## `machine_index.parquet` (Machine Metadata)

| Column            | Meaning                                           |
| ----------------- | ------------------------------------------------- |
| `machine_id`      | RecipeMap ID                                      |
| `display_name`    | Friendly machine name                             |
| `declaring_field` | Declaring field in `RecipeMaps`                   |
| `recipe_count`    | Recipe count in the map                           |
| `meta_tile_id`    | GregTech MetaTileEntity ID                        |
| `meta_tile_name`  | MetaTileEntity internal name                      |
| `meta_tile_class` | MetaTileEntity class name                         |
| `parallel_bonus`  | Parallel bonus multiplier (nullable)              |
| `max_parallel`    | Absolute max parallel operations (nullable)       |
| `coil_bonus`      | Coil-derived speed/energy bonus (nullable)        |
| `speed_bonus`     | Internal speed multiplier (nullable)              |
| `efficiency_bonus`| Efficiency multiplier (nullable)                  |
| `tooltip_derived` | True when a bonus value was sourced from tooltip  |

Bonus fields are nullable and intended to be enriched with GT++-specific details.

---

## `datapackage.json` (Schema Metadata)

Schema documentation is written in the Frictionless Data Package format and includes
per-file and per-column descriptions for downstream systems.

---

## Repository Structure

```
gtnh-recipe-extractor/
  README.md
  docker-compose.yml
  .env.example

  runner/
    Dockerfile
    entrypoint.sh
    convert_to_parquet.py

  dumper-mod/
    Dockerfile.build
    build.gradle
    gradle.properties
    settings.gradle
    src/main/resources/mcmod.info
    src/main/java/com/jamie/gtnh/recipedumper/
      RecipeDumperMod.java
      gt/GTReflectionDump.java

  out/                  # Parquet output
  cache/server/          # Optional server cache
```

---

## Prerequisites

* **Docker** (Desktop or Engine)
* **Docker Compose v2**

That’s it.
No Java, Gradle, or Minecraft tooling is required on the host.

---

## Quick Start (Docker-only)

### 1) Clone the repository

```bash
git clone <repo-url>
cd gtnh-recipe-extractor
```

---

### 2) Configure environment

Copy the example environment file:

```bash
cp .env.example .env
```

Edit `.env` and set a **direct GTNH server ZIP download URL**:

```env
GTNH_SERVER_ZIP_URL=https://<direct-server-zip-url>

# Optional JVM tuning
JAVA_XMS=2G
JAVA_XMX=6G
```

⚠️ The URL **must** point to a **dedicated server ZIP**, not an HTML page or launcher.

---

### 3) Build + run the extractor

This single command will:

* build the dumper mod in Docker
* start the GTNH server headlessly
* dump GregTech recipes
* convert the dump to Parquet
* shut everything down cleanly

```bash
docker compose up --build
```

---

## Results

After completion:

```
out/parquet/
  recipe_maps.parquet
  machine_index.parquet
  recipes.parquet
  edges.parquet
  item_inputs.parquet
  item_outputs.parquet
  fluid_inputs.parquet
  fluid_outputs.parquet
  datapackage.json
  _meta.json
out/recipes.json
out/machine_index.json
```

If something fails, logs may be copied into `out/` for inspection.

---

## How the Docker-only Build Works

### Services

* **`mod-build`**

  * Uses `gradle:4.10.3-jdk8`
  * Builds the Forge 1.7.10 dumper mod
  * Writes `RecipeDumper.jar` to `dumper-mod/build/libs/`

* **`gtnh-dump`**

  * Downloads the GTNH server ZIP
  * Mounts the dumper mod into `mods/`
  * Runs the server once
  * Converts the dump to Parquet
  * Exits automatically

No Gradle wrapper is checked into the repo.

---

## Troubleshooting

### `out/parquet` is empty

* Check `out/latest.log`
* Verify `GTNH_SERVER_ZIP_URL` is a **direct download**
* First GTNH startup can take several minutes

### Zero RecipeMaps detected

* GregTech internal class names may differ for that GTNH version
* Update `RECIPE_MAPS_CANDIDATES` in:

  ```
  dumper-mod/src/main/java/.../GTReflectionDump.java
  ```

### Server exits early

* Increase heap in `.env` (`JAVA_XMX`)
* Some GTNH server ZIPs use unusual start scripts — logs will indicate this

---

## Design Notes

* GregTech recipes are extracted **directly from RecipeMaps**, not via NEI UI scraping
* Non-GT recipes are extracted from **vanilla registries** and **mod-specific managers** when available
* Reflection is used intentionally to tolerate GTNH version drift
* Parquet conversion happens **outside Minecraft** for reliability
* Output is stable, diffable, and versionable per GTNH release

