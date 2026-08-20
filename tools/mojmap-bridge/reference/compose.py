#!/usr/bin/env python3
"""SRG-pivot composition: obf(1.13.2) -> Mojmap(1.14.4).

Namespaces emitted in tiny v2: official (=obf13) -> mojmap.

SNAPSHOT / REFERENCE ONLY. This is the validated spike algorithm, kept as the durable reference
that the Java generator (MojmapBridgeGen) ports and hardens. It is NOT run by the build. It has two
known defects that the Java port fixes:
  1. The class vote applies the tie-free-unique-top + minimum-margin guard only to tier 2. Tier 1 has
     no guard, which lets an ambiguous top vote bind (the LecternBlock false-positive source). The
     port guards BOTH tiers.
  2. There is no descriptor-fallback pass. Members whose searge id churned between 1.13.2 and 1.14.4
     (e.g. CompoundTag.put, whose return type went void -> Tag) are simply dropped. The port adds a
     fallback keyed on (matched-mojmap-owner, normalized parameter descriptor).
The port additionally adds a structural cross-check, the collision passthrough, the intermediary
oracle gate, and deterministic byte-stable output.
"""
import re
import sys
from collections import Counter, defaultdict

RECON = "/tmp/claude-1000/-mnt-c-data-ws-git-minecraft-mods-wdl/c9049057-2435-42cd-85b6-ef52210f94ca/scratchpad/recon"
SCRATCH = RECON + "/compose"

MOJ_FILES = [RECON + "/moj_client_1144.txt", RECON + "/moj_server_1144.txt"]
TSRG14 = RECON + "/joined_1144.tsrg"
TSRG13 = RECON + "/joined_1132.tsrg"
FIELDS13 = SCRATCH + "/fields1132.txt"
OUT_TINY = SCRATCH + "/mc1132-official-mojmap.tiny"

PRIM = {'void': 'V', 'boolean': 'Z', 'byte': 'B', 'char': 'C', 'short': 'S',
        'int': 'I', 'long': 'J', 'float': 'F', 'double': 'D'}

# ------------------------------------------------------------------ #
# 1) Parse Mojang ProGuard (mojmap -> obf14)
# ------------------------------------------------------------------ #
moj_class_m2o = {}          # mojmap internal -> obf14 internal
obf14_to_moj = {}           # obf14 internal -> mojmap internal
# member maps keyed in obf14 space
moj_method = {}             # (obf14_owner, obf14_name, obf14_desc) -> mojmap method name
moj_field = {}              # (obf14_owner, obf14_name) -> mojmap field name

CLASS_RE = re.compile(r'^([^ ]+) -> ([^ :]+):$')
METHOD_RE = re.compile(r'^(?:\d+:\d+:)?([^ ]+) ([^ (]+)\((.*)\) -> ([^ ]+)$')
FIELD_RE = re.compile(r'^([^ ]+) ([^ ]+) -> ([^ ]+)$')


def type_to_desc(t):
    dims = 0
    while t.endswith('[]'):
        dims += 1
        t = t[:-2]
    if t in PRIM:
        base = PRIM[t]
    else:
        internal = t.replace('.', '/')
        obf = moj_class_m2o.get(internal, internal)
        base = 'L' + obf + ';'
    return '[' * dims + base


def parse_moj(path):
    cur_moj = None      # current class mojmap internal
    cur_obf = None      # current class obf14 internal
    with open(path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if not line or line.startswith('#'):
                continue
            if line[0] not in ' \t':
                m = CLASS_RE.match(line)
                if not m:
                    continue
                deobf = m.group(1).replace('.', '/')
                obf = m.group(2).replace('.', '/')
                moj_class_m2o[deobf] = obf
                obf14_to_moj[obf] = deobf
                cur_moj, cur_obf = deobf, obf
            else:
                s = line.strip()
                if cur_obf is None:
                    continue
                if '(' in s:
                    m = METHOD_RE.match(s)
                    if not m:
                        continue
                    ret, name, params, obfname = m.groups()
                    param_list = [p for p in params.split(',') if p]
                    desc = '(' + ''.join(type_to_desc(p) for p in param_list) + ')' + type_to_desc(ret)
                    moj_method[(cur_obf, obfname, desc)] = name
                else:
                    m = FIELD_RE.match(s)
                    if not m:
                        continue
                    ftype, name, obfname = m.groups()
                    moj_field[(cur_obf, obfname)] = name


# NOTE: moj_class_m2o must be fully populated before we build descriptors,
# because a method param may reference a class defined later in the file.
# So we do a two-pass: pass 1 classes only, pass 2 members.
def parse_moj_classes_only(path):
    with open(path, 'r', encoding='utf-8') as f:
        for line in f:
            if not line or line[0] in ' \t#':
                continue
            line = line.rstrip('\n')
            m = CLASS_RE.match(line)
            if m:
                deobf = m.group(1).replace('.', '/')
                obf = m.group(2).replace('.', '/')
                moj_class_m2o[deobf] = obf
                obf14_to_moj[obf] = deobf


def parse_moj_members(path):
    cur_obf = None
    with open(path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if not line or line.startswith('#'):
                continue
            if line[0] not in ' \t':
                m = CLASS_RE.match(line)
                if m:
                    cur_obf = m.group(2).replace('.', '/')
                else:
                    cur_obf = None
            else:
                if cur_obf is None:
                    continue
                s = line.strip()
                if '(' in s:
                    m = METHOD_RE.match(s)
                    if not m:
                        continue
                    ret, name, params, obfname = m.groups()
                    param_list = [p for p in params.split(',') if p]
                    desc = '(' + ''.join(type_to_desc(p) for p in param_list) + ')' + type_to_desc(ret)
                    moj_method[(cur_obf, obfname, desc)] = name
                else:
                    m = FIELD_RE.match(s)
                    if not m:
                        continue
                    ftype, name, obfname = m.groups()
                    moj_field[(cur_obf, obfname)] = name


for p in MOJ_FILES:
    parse_moj_classes_only(p)
for p in MOJ_FILES:
    parse_moj_members(p)

print(f"[moj] classes={len(moj_class_m2o)} methods={len(moj_method)} fields={len(moj_field)}", file=sys.stderr)

# ------------------------------------------------------------------ #
# 2) Parse joined_1144.tsrg (obf14 -> srg), compose to srg->mojmap
# ------------------------------------------------------------------ #
srg_method_to_moj = {}       # srg name -> mojmap method name
srg_field_to_moj = {}        # srg name -> mojmap field name
srg_owner_sets = defaultdict(set)   # srg -> set of mojmap owner classes that list it in 1.14.4

m14_hit = m14_miss = f14_hit = f14_miss = 0
cur = None
with open(TSRG14, 'r', encoding='utf-8') as f:
    for line in f:
        line = line.rstrip('\n')
        if not line:
            continue
        if line[0] != '\t':
            cur = line.split(' ', 1)[0]
        else:
            parts = line[1:].split()
            if len(parts) == 2:
                obf_name, srg = parts
                moj = moj_field.get((cur, obf_name))
                if moj is not None:
                    srg_field_to_moj[srg] = moj
                    mo = obf14_to_moj.get(cur)
                    if mo:
                        srg_owner_sets[srg].add(mo)
                    f14_hit += 1
                else:
                    f14_miss += 1
            elif len(parts) == 3:
                obf_name, obf_desc, srg = parts
                moj = moj_method.get((cur, obf_name, obf_desc))
                if moj is not None:
                    srg_method_to_moj[srg] = moj
                    mo = obf14_to_moj.get(cur)
                    if mo:
                        srg_owner_sets[srg].add(mo)
                    m14_hit += 1
                else:
                    m14_miss += 1

# Unique-owner srg members only carry a reliable class-identity signal.
# (fields are always unique-owner; only overridden methods are multi-owner.)
srg_unique_owner = {srg: next(iter(s)) for srg, s in srg_owner_sets.items() if len(s) == 1}
n_multi = sum(1 for s in srg_owner_sets.values() if len(s) > 1)
print(f"[14->srg] method hit={m14_hit} miss={m14_miss}  field hit={f14_hit} miss={f14_miss}", file=sys.stderr)
print(f"[14->srg] srg with unique owner={len(srg_unique_owner)} multi-owner(dropped from vote)={n_multi}", file=sys.stderr)

# ------------------------------------------------------------------ #
# 3) Parse joined_1132.tsrg (obf13 -> srg)
# ------------------------------------------------------------------ #
obf13_class_srgs = defaultdict(list)   # obf13 class -> [srg member names]
obf13_methods = []                     # (obf13_class, obf13_name, obf13_desc, srg)
obf13_fields = []                      # (obf13_class, obf13_name, srg)
obf13_classes_order = []               # preserve tsrg order

cur = None
with open(TSRG13, 'r', encoding='utf-8') as f:
    for line in f:
        line = line.rstrip('\n')
        if not line:
            continue
        if line[0] != '\t':
            cur = line.split(' ', 1)[0]
            obf13_classes_order.append(cur)
        else:
            parts = line[1:].split()
            if len(parts) == 2:
                obf_name, srg = parts
                obf13_fields.append((cur, obf_name, srg))
                obf13_class_srgs[cur].append(srg)
            elif len(parts) == 3:
                obf_name, obf_desc, srg = parts
                obf13_methods.append((cur, obf_name, obf_desc, srg))
                obf13_class_srgs[cur].append(srg)

print(f"[13] classes={len(obf13_classes_order)} methods={len(obf13_methods)} fields={len(obf13_fields)}", file=sys.stderr)

# ------------------------------------------------------------------ #
# 4) CLASS matching: unique-owner vote + greedy bijective assignment.
#    Each obf13 class votes (over its unique-owner srg members) for the
#    mojmap class that declares the most of them.  We then assign classes
#    one-to-one, strongest evidence first, so no mojmap class is claimed
#    twice (tiny-remapper rejects duplicate class targets, and a subclass
#    must not be pulled onto its base).
# ------------------------------------------------------------------ #
class_votes = {}   # obf13 class -> Counter(mojmap class -> score)
for cls in obf13_classes_order:
    votes = Counter()
    for srg in obf13_class_srgs.get(cls, ()):
        owner = srg_unique_owner.get(srg)
        if owner:
            votes[owner] += 1
    if votes:
        class_votes[cls] = votes

# candidate pairs (score, obf13, mojmap); assign greedily, highest score first.
pairs = []
for cls, votes in class_votes.items():
    for mcls, sc in votes.items():
        pairs.append((sc, cls, mcls))
pairs.sort(key=lambda t: (-t[0], t[1], t[2]))

obf13_to_moj_class = {}
vote_conf = {}      # obf13 -> (winner_score, total_votes)
taken_moj = set()
for sc, cls, mcls in pairs:
    if cls in obf13_to_moj_class or mcls in taken_moj:
        continue
    obf13_to_moj_class[cls] = mcls
    taken_moj.add(mcls)
    vote_conf[cls] = (sc, sum(class_votes[cls].values()))

# how many classes had votes but lost every candidate to a rival (rerouted to nothing)
n_voted = len(class_votes)
n_tier1 = len(obf13_to_moj_class)

# Tier 2: recover classes with no unique-owner signal (pure interfaces / abstract
# bases whose every method is overridden elsewhere, e.g. Packet).  Vote with the
# FULL owner sets but only over mojmap targets tier 1 did not already claim; the
# base's own identity is then the sole untaken target common to its members.
remaining = [c for c in obf13_classes_order
             if c not in obf13_to_moj_class and obf13_class_srgs.get(c)]
cand = []          # (score, cls, mcls) for classes with a UNIQUE (tie-free) top
n_tier2_tie_reject = 0
for cls in remaining:
    votes = Counter()
    for srg in obf13_class_srgs[cls]:
        for m in srg_owner_sets.get(srg, ()):
            if m not in taken_moj:
                votes[m] += 1
    if not votes:
        continue
    top = votes.most_common()
    win_m, win_s = top[0]
    if len(top) > 1 and top[1][1] == win_s:
        # ambiguous: >=2 untaken targets share the max (e.g. a block that only
        # overrides generic Block methods) -> not confident, leave unmapped.
        n_tier2_tie_reject += 1
        continue
    cand.append((win_s, cls, win_m))
cand.sort(key=lambda t: (-t[0], t[1], t[2]))
n_tier2 = 0
for sc, cls, mcls in cand:
    if cls in obf13_to_moj_class or mcls in taken_moj:
        continue
    obf13_to_moj_class[cls] = mcls
    taken_moj.add(mcls)
    vote_conf[cls] = (sc, -1)
    n_tier2 += 1
print(f"[vote] tier2 tie-rejected(left unmapped)={n_tier2_tie_reject}", file=sys.stderr)

n_assigned = len(obf13_to_moj_class)
print(f"[vote] tier1={n_tier1} tier2_recovered={n_tier2} total_assigned={n_assigned}", file=sys.stderr)

# ------------------------------------------------------------------ #
# 5) Field descriptors from the 1.13.2 jar
# ------------------------------------------------------------------ #
jar_field_desc = {}   # (obf13_class, obf13_name) -> desc
with open(FIELDS13, 'r', encoding='utf-8') as f:
    for line in f:
        owner, name, desc = line.rstrip('\n').split('\t')
        jar_field_desc[(owner, name)] = desc

# ------------------------------------------------------------------ #
# 6) Emit tiny v2
# ------------------------------------------------------------------ #
# Build per-class member emission lists.
methods_by_class = defaultdict(list)   # obf13_class -> [(obf_desc, obf_name, mojmap_name)]
fields_by_class = defaultdict(list)    # obf13_class -> [(obf_desc, obf_name, mojmap_name)]

n_method_mapped = 0
n_method_skip_nomap = 0
n_method_skip_identity = 0
for cls, name, desc, srg in obf13_methods:
    moj = srg_method_to_moj.get(srg)
    if moj is None:
        n_method_skip_nomap += 1
        continue
    if moj == name:
        n_method_skip_identity += 1
        continue
    methods_by_class[cls].append((desc, name, moj))
    n_method_mapped += 1

n_field_mapped = 0
n_field_skip_nomap = 0
n_field_skip_identity = 0
n_field_nodesc = 0
fields_nodesc_samples = []
for cls, name, srg in obf13_fields:
    moj = srg_field_to_moj.get(srg)
    if moj is None:
        n_field_skip_nomap += 1
        continue
    if moj == name:
        n_field_skip_identity += 1
        continue
    desc = jar_field_desc.get((cls, name))
    if desc is None:
        n_field_nodesc += 1
        if len(fields_nodesc_samples) < 20:
            fields_nodesc_samples.append((cls, name, srg, moj))
        continue
    fields_by_class[cls].append((desc, name, moj))
    n_field_mapped += 1

# Which classes get a c-line: any obf13 class that is either mapped OR has mapped members.
emit_classes = set(obf13_to_moj_class) | set(methods_by_class) | set(fields_by_class)

n_class_renamed = 0
with open(OUT_TINY, 'w', encoding='utf-8') as w:
    w.write("tiny\t2\t0\tofficial\tmojmap\n")
    for cls in obf13_classes_order:
        if cls not in emit_classes:
            continue
        target = obf13_to_moj_class.get(cls, cls)
        if target != cls:
            n_class_renamed += 1
        w.write(f"c\t{cls}\t{target}\n")
        for desc, name, moj in methods_by_class.get(cls, ()):
            w.write(f"\tm\t{desc}\t{name}\t{moj}\n")
        for desc, name, moj in fields_by_class.get(cls, ()):
            w.write(f"\tf\t{desc}\t{name}\t{moj}\n")

print(f"[emit] class c-lines={len(emit_classes)} renamed={n_class_renamed}", file=sys.stderr)
print(f"[emit] methods mapped={n_method_mapped} skip_nomap={n_method_skip_nomap} skip_identity={n_method_skip_identity}", file=sys.stderr)
print(f"[emit] fields  mapped={n_field_mapped} skip_nomap={n_field_skip_nomap} skip_identity={n_field_skip_identity} nodesc={n_field_nodesc}", file=sys.stderr)
if fields_nodesc_samples:
    print("[emit] field-nodesc samples (cls,name,srg,moj):", file=sys.stderr)
    for s in fields_nodesc_samples:
        print("   ", s, file=sys.stderr)

# Emit some counts to a stats file too.
with open(SCRATCH + "/compose_stats.txt", "w") as sf:
    sf.write(f"moj_classes={len(moj_class_m2o)}\n")
    sf.write(f"moj_methods={len(moj_method)}\n")
    sf.write(f"moj_fields={len(moj_field)}\n")
    sf.write(f"srg_method_to_moj={len(srg_method_to_moj)}\n")
    sf.write(f"srg_field_to_moj={len(srg_field_to_moj)}\n")
    sf.write(f"obf13_classes={len(obf13_classes_order)}\n")
    sf.write(f"class_renamed={n_class_renamed}\n")
    sf.write(f"class_clines={len(emit_classes)}\n")
    sf.write(f"class_unassigned_after_bijection={n_voted - n_assigned}\n")
    sf.write(f"method_mapped={n_method_mapped}\n")
    sf.write(f"field_mapped={n_field_mapped}\n")
    sf.write(f"field_nodesc={n_field_nodesc}\n")
    sf.write(f"method_skip_nomap={n_method_skip_nomap}\n")
    sf.write(f"field_skip_nomap={n_field_skip_nomap}\n")
print("wrote", OUT_TINY, file=sys.stderr)
