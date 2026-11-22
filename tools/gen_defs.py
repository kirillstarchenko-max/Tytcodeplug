#!/usr/bin/env python3
"""Генерирует Uv380Defs.kt из codeplugs.json (dalefarnsworth-dmr/codeplug, LGPL-3.0).

Запуск: python3 -I tools/gen_defs.py tools/ref/codeplugs.json app/src/main/java/com/tytcp/app/codeplug/def/Uv380Defs.kt
"""
import json, sys

src, dst = sys.argv[1], sys.argv[2]
d = json.load(open(src, encoding="utf-8"))
cp = [c for c in d["codeplugs"] if c["type"] == "MD-UV380"][0]
recs = {r["type"]: r for r in d["records"]}
flds = {f["type"]: f for f in d["fields"]}


def ks(s):
    return json.dumps(s, ensure_ascii=False).replace("$", "\\$")


def opt(v, fn=str):
    return "null" if v is None else fn(v)


def strlist(l):
    return "null" if l is None else "listOf(" + ", ".join(ks(x) for x in l) + ")"


def base(t):
    return None if t is None else t.split("_")[0]


out = []
w = out.append
w("// СГЕНЕРИРОВАНО tools/gen_defs.py — не редактировать вручную.")
w("// Описание полей: dalefarnsworth-dmr/codeplug codeplugs.json,")
w("// Copyright 2017-2021 Dale Farnsworth, LGPL-3.0 (assets/LICENSE-LGPL-3.0).")
w("package com.tytcp.app.codeplug.def")
w("")
w("object Uv380Defs {")
w("    val records: List<RecordDef> = listOf(")
for rt in cp["recordTypes"]:
    r = recs[rt]
    dd = r.get("delDesc")
    w(f"        RecordDef(")
    w(f"            type = {ks(base(rt))}, name = {ks(r['typeName'])}, rdtOffset = {r['offset']}, size = {r['size']},")
    w(f"            max = {r.get('max', 1)}, del = {('DelDesc(%d, %d, %d)' % (dd['offset'], dd['size'], dd['value'])) if dd else 'null'},")
    w(f"            names = {strlist(r.get('names'))}, namePrefix = {opt(r.get('namePrefix'), ks)},")
    w(f"            fields = listOf(")
    for ft in r["fieldTypes"]:
        f = flds[ft]
        sp = f.get("span")
        spk = "null" if sp is None else "Span(%d, %d, %d, %d, %s)" % (
            sp.get("min", 0), sp.get("max", 0), sp.get("scale", 1) or 1, sp.get("interval", 1) or 1,
            opt(sp.get("minString"), ks))
        isx = f.get("indexedStrings")
        isk = "null" if isx is None else "listOf(" + ", ".join("IdxStr(%d, %s)" % (x["index"], ks(x["string"])) for x in isx) + ")"
        w(f"                FieldDef(")
        w(f"                    type = {ks(ft)}, name = {ks(f['typeName'])}, bitOffset = {f['bitOffset']}, bitSize = {f['bitSize']},")
        w(f"                    valueType = {ks(f['valueType'])}, default = {opt(f.get('defaultValue'), ks)}, max = {f.get('max', 1)},")
        w(f"                    strings = {strlist(f.get('strings'))}, indexed = {isk}, span = {spk}, listType = {opt(base(f.get('listType')), ks)},")
        w(f"                    extRdtOffset = {f.get('extOffset', 0)}, extBitOffset = {f.get('extBitOffset', 0)}, extSize = {f.get('extSize', 0)}, extIndex = {f.get('extIndex', 0)},")
        w(f"                ),")
    w(f"            ),")
    w(f"        ),")
w("    )")
w("")
w("    private val byType = records.associateBy { it.type }")
w("    fun record(type: String): RecordDef = byType.getValue(type)")
w("}")
open(dst, "w", encoding="utf-8").write("\n".join(out) + "\n")
print("records:", len(cp["recordTypes"]))
