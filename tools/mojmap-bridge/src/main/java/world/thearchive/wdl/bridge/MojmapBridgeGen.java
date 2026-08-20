// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later
package world.thearchive.wdl.bridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.adapter.MappingDstNsReorder;
import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

/**
 * Generates the 1.13.2 Mojmap bridge mappings by SRG-pivot composition.
 *
 * <p>Composes obf(1.13.2) -&gt; Mojmap(1.14.4) from three public files (Mojang 1.14.4 ProGuard
 * mappings, mcp_config 1.14.4 joined.tsrg, mcp_config 1.13.2 joined.tsrg) using the searge member
 * ids as the version-stable pivot, hardened with a per-tier tie/margin vote guard, a structural
 * class cross-check, and a descriptor-fallback pass for churned-srg members. From the single
 * resulting obf-&gt;mojmap map it derives an intermediary-&gt;named mappings jar (for Fabric Loom) and
 * a mojmap-&gt;srg reobf mapping.
 *
 * <p>This is a build-time tool, not shipped code; it is intentionally a single self-contained class.
 */
public final class MojmapBridgeGen {

    // Pinned immutable coordinates (determinism).
    private static final String MCP_1132 = "1.13.2-20190213.203750";
    private static final String MCP_1144 = "1.14.4-20190829.143755";
    private static final String FORGE_MAVEN = "https://maven.minecraftforge.net/";
    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String INTERMEDIARY_URL =
            "https://maven.legacyfabric.net/net/legacyfabric/intermediary/1.13.2/intermediary-1.13.2-v2.jar";
    // Pinned sha1 for the 1.13.2 client jar (from the 1.13.2 version manifest entry).
    private static final String CLIENT_1132_SHA1 = "30bfe37a8db404db11c7edf02cb5165817afb4d9";

    private static final Map<String, String> PRIM = Map.ofEntries(
            Map.entry("void", "V"), Map.entry("boolean", "Z"), Map.entry("byte", "B"),
            Map.entry("char", "C"), Map.entry("short", "S"), Map.entry("int", "I"),
            Map.entry("long", "J"), Map.entry("float", "F"), Map.entry("double", "D"));

    private static final Pattern CLASS_RE = Pattern.compile("^([^ ]+) -> ([^ :]+):$");
    private static final Pattern METHOD_RE = Pattern.compile("^(?:\\d+:\\d+:)?([^ ]+) ([^ (]+)\\((.*)\\) -> ([^ ]+)$");
    private static final Pattern FIELD_RE = Pattern.compile("^([^ ]+) ([^ ]+) -> ([^ ]+)$");

    // ---- ProGuard-derived maps (1.14.4) -------------------------------------------------------
    /** mojmap internal class -> obf14 internal class. */
    private final Map<String, String> mojClassM2o = new HashMap<>();
    /** obf14 internal class -> mojmap internal class. */
    private final Map<String, String> obf14ToMoj = new HashMap<>();
    /** (obf14 owner, obf14 name, obf14 desc) -> mojmap method name. */
    private final Map<String, String> mojMethod = new HashMap<>();
    /** (obf14 owner, obf14 name) -> mojmap field name. */
    private final Map<String, String> mojField = new HashMap<>();
    /** mojmap owner -> (mojmap-space method desc -> set of mojmap method names). For desc-fallback. */
    private final Map<String, Map<String, Set<String>>> mojMethodsByOwnerDesc = new HashMap<>();

    // ---- 1.14.4 tsrg (obf14 -> srg) joined to mojmap ------------------------------------------
    private final Map<String, String> srgMethodToMoj = new HashMap<>();
    private final Map<String, String> srgFieldToMoj = new HashMap<>();
    /** srg id -> set of mojmap owner classes that declare it in 1.14.4. */
    private final Map<String, Set<String>> srgOwnerSets = new HashMap<>();
    /** srg id -> the unique mojmap owner (only for srg ids owned by exactly one class). */
    private final Map<String, String> srgUniqueOwner = new HashMap<>();

    // ---- 1.13.2 tsrg (obf13 -> srg) -----------------------------------------------------------
    private final List<String> obf13ClassOrder = new ArrayList<>();
    private final Map<String, List<String>> obf13ClassSrgs = new HashMap<>();
    /** obf13 methods: [owner, name, desc, srg]. */
    private final List<String[]> obf13Methods = new ArrayList<>();
    /** obf13 fields: [owner, name, srg]. */
    private final List<String[]> obf13Fields = new ArrayList<>();
    /** obf13 class -> srg class name (from joined_1132). */
    private final Map<String, String> obf13ClassToSrg = new HashMap<>();

    // ---- jar structural data ------------------------------------------------------------------
    private final Map<String, ClassInfo> jar13 = new HashMap<>();
    private final Map<String, ClassInfo> jar14 = new HashMap<>();

    // ---- results ------------------------------------------------------------------------------
    private final Map<String, String> obf13ToMojClass = new HashMap<>();
    // Numeric-anonymous inner classes dropped from the class map so Loom's ordinal-preserving
    // completion names them uniquely, rather than the vote's ordinal-swap colliding with the completion.
    private final Set<String> excludedAnons = new TreeSet<>();

    // ---- merged-jar hierarchy (client + server-only classes) ----------------------------------
    // superclass, interfaces, and declared virtual method slots per class, for the member-propagation
    // consistency pass and the remap gate. Loom merges client+server before remapping.
    private final Map<String, MergedClass> merged = new HashMap<>();

    // official class -> Legacy Fabric intermediary class name (net/minecraft/class_NNNN), used to
    // detect the root-package landing of an unmapped class and to name its package-preserving fallback.
    private final Map<String, String> officialToInter = new HashMap<>();

    // Legacy Fabric intermediary member names, the name Loom leaves on the dev classpath for every member
    // the bridge does not Mojmap-map. Keyed by official owner + obf name (+ obf desc for methods, since a
    // class reuses an obf method name across descriptors). The field value carries the obf descriptor too,
    // since obf13Fields (from the MCP tsrg) does not record one.
    private final Map<String, String> officialToInterMethod = new HashMap<>();
    private final Map<String, String[]> officialToInterField = new HashMap<>();

    // counters
    private int classesVoted;
    private int classesAssigned;
    private int classesBumpedOffTop;
    private int classesTieRejected;
    private int classesStructRejected;
    private int classesStructSuspect;
    private int classesLeftUnmapped;
    private int collisionsResolved;
    private int membersSrgMapped;
    private int membersDescfallbackMapped;
    private int membersLeftUnmapped;
    private int membersChurnedTypeUnmapped;
    private int membersHierarchyPropagated;
    private int membersHierarchyUnmapped;
    private int hierarchyDivergentSlots;
    private int fallbackPackagedByAncestor;
    private int fallbackPackagedByCoupling;
    private int fallbackPackagedBySrg;

    // ---- coupling data (protected/package-private cross-class access between MC classes) ----------
    /** obf class -> obf classes that directly extend or implement it (Minecraft classes only). */
    private final Map<String, List<String>> mcChildren = new HashMap<>();
    /** non-public cross-class member-access edges as [accessor, declaringClass, accessFlags]. */
    private final List<String[]> couplingEdges = new ArrayList<>();
    /** top-level orphan obf class -> Mojmap package of the mapped class it is same-package-coupled to. */
    private final Map<String, String> orphanCoupledPackage = new HashMap<>();
    /** obf classes we assigned a package-preserving fallback name (not real Mojmap). */
    private final Set<String> fallbackClasses = new HashSet<>();

    static final class ClassInfo {
        String superName;
        final List<String> interfaces = new ArrayList<>();
        /** field name -> descriptor. */
        final Map<String, String> fields = new TreeMap<>();
    }

    static final class MergedClass {
        int access;
        String superName;
        final List<String> interfaces = new ArrayList<>();
        /** declared virtual methods (non-static, non-private; not <init>/<clinit>), as "name\tdesc". */
        final Set<String> virtualMethods = new TreeSet<>();
        /** declared members (methods and fields) -> access flags, for coupling access checks. Only
         * populated for Minecraft classes (in the srg map); libraries do not need it. */
        Map<String, Integer> memberAccess;
    }

    public static void main(String[] args) throws Exception {
        Path outDir = null;
        Path cacheDir = null;
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--out")) {
                outDir = Path.of(args[i + 1]);
            } else if (args[i].equals("--cache")) {
                cacheDir = Path.of(args[i + 1]);
            }
        }
        if (outDir == null || cacheDir == null) {
            System.err.println("usage: MojmapBridgeGen --out <dir> --cache <dir>");
            System.exit(2);
        }
        new MojmapBridgeGen().run(outDir, cacheDir);
    }

    void run(Path outDir, Path cacheDir) throws Exception {
        Files.createDirectories(outDir);
        Files.createDirectories(cacheDir);

        Sources src = ensureSources(cacheDir);

        parseProguard(src.clientMappings1144);
        parseProguard(src.serverMappings1144);
        parseProguardMembers();
        log("[moj] classes=%d methods=%d fields=%d", mojClassM2o.size(), mojMethod.size(), mojField.size());

        parseJoined1144(src.joined1144);
        log("[14->srg] srgWithUniqueOwner=%d", srgUniqueOwner.size());

        parseJoined1132(src.joined1132);
        log("[13] classes=%d methods=%d fields=%d", obf13ClassOrder.size(), obf13Methods.size(), obf13Fields.size());

        readJar(src.client1132, jar13);
        readJar(src.client1144, jar14);
        log("[jar] classes13=%d classes14=%d", jar13.size(), jar14.size());

        voteClasses();
        log("[vote] voted=%d assigned(pre-struct)=%d tieRejected=%d bumped=%d",
                classesVoted, obf13ToMojClass.size(), classesTieRejected, classesBumpedOffTop);

        structuralCrossCheck();
        classesAssigned = obf13ToMojClass.size();
        classesLeftUnmapped = classesVoted - classesAssigned;
        log("[struct] structRejected=%d finalAssigned=%d leftUnmapped=%d",
                classesStructRejected, classesAssigned, classesLeftUnmapped);

        // member maps built per class
        Map<String, TreeMap<String, String[]>> methodsByClass = new HashMap<>();
        Map<String, TreeMap<String, String[]>> fieldsByClass = new HashMap<>();
        mapMembers(methodsByClass, fieldsByClass);
        log("[members] srgMapped=%d descfallback=%d churnedTypeUnmapped=%d leftUnmapped=%d",
                membersSrgMapped, membersDescfallbackMapped, membersChurnedTypeUnmapped, membersLeftUnmapped);

        // Merged client+server class set (Loom merges before remap; collisions can involve
        // server-only classes), used both for injective-collision resolution and the remap gate.
        readMergedStructure(src.client1132, src.server1132);
        loadIntermediaryNames(src.intermediaryTiny);
        Set<String> mergedClasses = new TreeSet<>(merged.keySet());
        mergedClasses.addAll(obf13ToMojClass.keySet());
        log("[merged] classes=%d", mergedClasses.size());

        // Give every unmapped class a package-preserving fallback name so Loom does not strand it at
        // its flat root-package intermediary name (net/minecraft/class_NNNN) and break vanilla's own
        // same-package access. Runs before collision resolution so the anon exclusion sees the fallback
        // anchors as renamed. computeOrphanCoupledPackages first scans the merged jar for the hierarchy
        // and member couplings that bind an orphan to a mapped class's package.
        computeOrphanCoupledPackages(src.client1132, src.server1132);
        packageUnmappedClasses();

        // Collision resolution: drop numeric-anonymous inners so Loom's ordinal-preserving completion
        // names them uniquely, and blank the Realms passthrough intruder.
        Set<String> blanked = resolveCollisions(mergedClasses);
        for (String c : excludedAnons) {
            methodsByClass.remove(c);
            fieldsByClass.remove(c);
        }
        // A class blanked to resolve a collision is now unmapped and would itself land in the root
        // package, so package the newly-blanked classes too.
        packageUnmappedClasses();
        log("[package] fallbackByAncestor=%d fallbackByCoupling=%d fallbackBySrg=%d",
                fallbackPackagedByAncestor, fallbackPackagedByCoupling, fallbackPackagedBySrg);
        log("[collision] anonsExcluded=%d realmsBlanked=%d resolved=%d",
                excludedAnons.size(), blanked.size(), collisionsResolved);

        // Make method overrides hierarchy-consistent so tiny-remapper's member propagation does not
        // abort Loom's remap with "Unfixable conflicts". Runs after the anon drop, then re-adds the
        // overrides needed to make each slot consistent (anons included, hosted under a blank class
        // line). The toggle exists only to prove the remap gate reproduces the pre-fix failure.
        if (System.getenv("BRIDGE_SKIP_HIERARCHY_FIX") == null) {
            enforceHierarchyConsistency(methodsByClass);
        }
        log("[hierarchy] propagated=%d divergentSlots=%d unmapped=%d",
                membersHierarchyPropagated, hierarchyDivergentSlots, membersHierarchyUnmapped);

        // emit obf-mojmap.tiny (hand-written, deterministic) from the one map
        Path obfMojmap = outDir.resolve("obf-mojmap.tiny");
        emitObfMojmap(obfMojmap, methodsByClass, fieldsByClass);
        log("[emit] wrote %s", obfMojmap);

        // load the one map via mapping-io, derive the two outputs
        MemoryMappingTree oneMap = new MemoryMappingTree();
        MappingReader.read(obfMojmap, MappingFormat.TINY_2_FILE, oneMap);

        Path interNamedTiny = outDir.resolve("intermediary-mojmap.tiny");
        Path interNamedJar = outDir.resolve("intermediary-mojmap.jar");
        deriveIntermediaryNamed(oneMap, src.intermediaryTiny, interNamedTiny, interNamedJar);
        log("[emit] wrote %s and %s", interNamedTiny, interNamedJar);

        Path mojmapSrg = outDir.resolve("mojmap-srg.tiny");
        deriveMojmapSrg(mojmapSrg, methodsByClass, fieldsByClass, blanked);
        log("[emit] wrote %s", mojmapSrg);

        boolean ok = true;
        ok &= oracleGate(src.intermediaryTiny);
        ok &= selfValidate(methodsByClass, fieldsByClass, blanked);
        ok &= intermediaryRemainderGate(mojmapSrg);
        ok &= classRemainderGate(mojmapSrg);
        ok &= couplingGate();
        ok &= remapApplyGate(mergedClasses, src.client1132, src.server1132, src.intermediaryTiny,
                methodsByClass, fieldsByClass);

        printCounters();

        if (!ok) {
            System.err.println("SELF-VALIDATION FAILED");
            System.exit(1);
        }
        log("OK");
    }

    // ============================================================================================
    // ProGuard parsing (mojmap -> obf14), two-pass (classes then members).
    // ============================================================================================
    private final List<Path> proguardPaths = new ArrayList<>();

    private void parseProguard(Path path) throws IOException {
        proguardPaths.add(path);
        // pass 1: classes
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '\t' || line.charAt(0) == '#') {
                    continue;
                }
                Matcher m = CLASS_RE.matcher(line);
                if (m.matches()) {
                    String deobf = m.group(1).replace('.', '/');
                    String obf = m.group(2).replace('.', '/');
                    mojClassM2o.put(deobf, obf);
                    obf14ToMoj.put(obf, deobf);
                }
            }
        }
    }

    /** Second pass over all ProGuard files: members (needs mojClassM2o fully populated). */
    private void parseProguardMembers() throws IOException {
        for (Path path : proguardPaths) {
            String curObf = null;
            String curMoj = null;
            try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') {
                        continue;
                    }
                    if (line.charAt(0) != ' ' && line.charAt(0) != '\t') {
                        Matcher m = CLASS_RE.matcher(line);
                        if (m.matches()) {
                            curMoj = m.group(1).replace('.', '/');
                            curObf = m.group(2).replace('.', '/');
                        } else {
                            curObf = null;
                            curMoj = null;
                        }
                        continue;
                    }
                    if (curObf == null) {
                        continue;
                    }
                    String s = line.strip();
                    if (s.indexOf('(') >= 0) {
                        Matcher m = METHOD_RE.matcher(s);
                        if (!m.matches()) {
                            continue;
                        }
                        String ret = m.group(1);
                        String name = m.group(2);
                        String params = m.group(3);
                        String obfname = m.group(4);
                        // Mojang lists the static initializer and constructors (void <clinit>() -> <clinit>,
                        // void <init>(...) -> <init>). These JVM special methods are never renamed; feeding
                        // them into the pivot yields invalid mapping targets that tiny-remapper rejects.
                        if (name.equals("<init>") || name.equals("<clinit>")) {
                            continue;
                        }
                        String obfDesc = methodDesc(params, ret, true);
                        mojMethod.put(curObf + "\t" + obfname + "\t" + obfDesc, name);
                        // parameter-only descriptor (mojmap space) for the fallback index: the
                        // return type churns across versions (e.g. CompoundTag.put went void->Tag),
                        // and Java forbids two methods with the same name and parameters, so the
                        // parameter descriptor is the stable member identity within a class.
                        String mojParams = paramDesc(params, false);
                        mojMethodsByOwnerDesc
                                .computeIfAbsent(curMoj, k -> new HashMap<>())
                                .computeIfAbsent(mojParams, k -> new LinkedHashSet<>())
                                .add(name);
                    } else {
                        Matcher m = FIELD_RE.matcher(s);
                        if (!m.matches()) {
                            continue;
                        }
                        String name = m.group(2);
                        String obfname = m.group(3);
                        mojField.put(curObf + "\t" + obfname, name);
                    }
                }
            }
        }
    }

    /**
     * Builds a JVM method descriptor from ProGuard param/return types.
     *
     * @param obfSpace when true, object types are mapped mojmap-&gt;obf14 (for joining with
     *     joined_1144); when false they stay in mojmap-internal space (for the fallback index).
     */
    private String methodDesc(String params, String ret, boolean obfSpace) {
        return paramDesc(params, obfSpace) + typeToDesc(ret, obfSpace);
    }

    private String paramDesc(String params, boolean obfSpace) {
        StringBuilder sb = new StringBuilder("(");
        if (!params.isEmpty()) {
            for (String p : params.split(",")) {
                if (!p.isEmpty()) {
                    sb.append(typeToDesc(p, obfSpace));
                }
            }
        }
        return sb.append(')').toString();
    }

    /** Returns just the parameter portion "(...)" of a JVM method descriptor. */
    private static String paramPart(String methodDescriptor) {
        return methodDescriptor.substring(0, methodDescriptor.indexOf(')') + 1);
    }

    private String typeToDesc(String t, boolean obfSpace) {
        int dims = 0;
        while (t.endsWith("[]")) {
            dims++;
            t = t.substring(0, t.length() - 2);
        }
        String base;
        if (PRIM.containsKey(t)) {
            base = PRIM.get(t);
        } else {
            String internal = t.replace('.', '/');
            if (obfSpace) {
                String obf = mojClassM2o.getOrDefault(internal, internal);
                base = "L" + obf + ";";
            } else {
                base = "L" + internal + ";";
            }
        }
        return "[".repeat(dims) + base;
    }

    // ============================================================================================
    // joined_1144.tsrg: obf14 -> srg, joined to srg -> mojmap via the ProGuard maps.
    // ============================================================================================
    private void parseJoined1144(Path path) throws IOException {
        String cur = null;
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.charAt(0) != '\t') {
                    cur = line.split(" ", 2)[0];
                    continue;
                }
                String[] parts = line.substring(1).trim().split("\\s+");
                if (parts.length == 2) {
                    String obfName = parts[0];
                    String srg = parts[1];
                    String moj = mojField.get(cur + "\t" + obfName);
                    if (moj != null) {
                        srgFieldToMoj.put(srg, moj);
                        String mo = obf14ToMoj.get(cur);
                        if (mo != null) {
                            srgOwnerSets.computeIfAbsent(srg, k -> new HashSet<>()).add(mo);
                        }
                    }
                } else if (parts.length == 3) {
                    String obfName = parts[0];
                    String obfDesc = parts[1];
                    String srg = parts[2];
                    String moj = mojMethod.get(cur + "\t" + obfName + "\t" + obfDesc);
                    if (moj != null) {
                        srgMethodToMoj.put(srg, moj);
                        String mo = obf14ToMoj.get(cur);
                        if (mo != null) {
                            srgOwnerSets.computeIfAbsent(srg, k -> new HashSet<>()).add(mo);
                        }
                    }
                }
            }
        }
        for (Map.Entry<String, Set<String>> e : srgOwnerSets.entrySet()) {
            if (e.getValue().size() == 1) {
                srgUniqueOwner.put(e.getKey(), e.getValue().iterator().next());
            }
        }
    }

    // ============================================================================================
    // joined_1132.tsrg: obf13 -> srg.
    // ============================================================================================
    private void parseJoined1132(Path path) throws IOException {
        String cur = null;
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.charAt(0) != '\t') {
                    String[] cp = line.split("\\s+");
                    cur = cp[0];
                    obf13ClassOrder.add(cur);
                    if (cp.length >= 2) {
                        obf13ClassToSrg.put(cur, cp[1]);
                    }
                    continue;
                }
                String[] parts = line.substring(1).trim().split("\\s+");
                if (parts.length == 2) {
                    obf13Fields.add(new String[] {cur, parts[0], parts[1]});
                    obf13ClassSrgs.computeIfAbsent(cur, k -> new ArrayList<>()).add(parts[1]);
                } else if (parts.length == 3) {
                    obf13Methods.add(new String[] {cur, parts[0], parts[1], parts[2]});
                    obf13ClassSrgs.computeIfAbsent(cur, k -> new ArrayList<>()).add(parts[2]);
                }
            }
        }
    }

    // ============================================================================================
    // ASM jar structural read.
    // ============================================================================================
    private void readJar(Path jar, Map<String, ClassInfo> into) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes = zis.readAllBytes();
                ClassInfo info = new ClassInfo();
                ClassReader cr = new ClassReader(bytes);
                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(int version, int access, String name, String signature,
                            String superName, String[] interfaces) {
                        info.superName = superName;
                        if (interfaces != null) {
                            for (String i : interfaces) {
                                info.interfaces.add(i);
                            }
                        }
                    }

                    @Override
                    public FieldVisitor visitField(int access, String name, String descriptor,
                            String signature, Object value) {
                        info.fields.put(name, descriptor);
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                into.put(cr.getClassName(), info);
            }
        }
    }

    /**
     * Reads the merged (client + server-only classes, matching Loom's merge) hierarchy: each class's
     * superclass, interfaces, and declared virtual method slots. A virtual method is non-static and
     * non-private ({@code access & 0xA == 0}, tiny-remapper's {@code isVirtual}); only these propagate
     * across the hierarchy, so only these can produce a source-name conflict.
     */
    private void readMergedStructure(Path client, Path server) throws IOException {
        readStructureInto(client);
        readStructureInto(server);
    }

    private void readStructureInto(Path jar) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes = zis.readAllBytes();
                ClassReader cr = new ClassReader(bytes);
                if (merged.containsKey(cr.getClassName())) {
                    continue; // client wins on a class present in both, matching the merge
                }
                MergedClass info = new MergedClass();
                boolean isMc = obf13ClassToSrg.containsKey(cr.getClassName());
                if (isMc) {
                    info.memberAccess = new HashMap<>();
                }
                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(int version, int access, String name, String signature,
                            String superName, String[] interfaces) {
                        info.access = access;
                        info.superName = superName;
                        if (interfaces != null) {
                            for (String i : interfaces) {
                                info.interfaces.add(i);
                            }
                        }
                    }

                    @Override
                    public FieldVisitor visitField(int access, String name, String descriptor,
                            String signature, Object value) {
                        if (info.memberAccess != null) {
                            info.memberAccess.put(name + "\t" + descriptor, access);
                        }
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                            String signature, String[] exceptions) {
                        if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0
                                && !name.equals("<init>") && !name.equals("<clinit>")) {
                            info.virtualMethods.add(name + "\t" + descriptor);
                        }
                        if (info.memberAccess != null) {
                            info.memberAccess.put(name + "\t" + descriptor, access);
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                merged.put(cr.getClassName(), info);
            }
        }
    }

    // ============================================================================================
    // Class voting (tier 1 unique-owner + tier 2 full-owner), tie/margin guard on BOTH tiers,
    // greedy bijective assignment.
    // ============================================================================================
    private static final int MIN_MARGIN = 1; // winner must beat runner-up by at least this many votes

    private void voteClasses() {
        // tier 1: unique-owner votes
        Map<String, TreeMap<String, Integer>> votes1 = new HashMap<>();
        for (String cls : obf13ClassOrder) {
            TreeMap<String, Integer> v = new TreeMap<>();
            for (String srg : obf13ClassSrgs.getOrDefault(cls, List.of())) {
                String owner = srgUniqueOwner.get(srg);
                if (owner != null) {
                    v.merge(owner, 1, Integer::sum);
                }
            }
            if (!v.isEmpty()) {
                votes1.put(cls, v);
            }
        }
        Set<String> votedClasses = new HashSet<>(votes1.keySet());

        // tier-1 candidate pairs that pass the tie-free-unique-top + min-margin guard
        List<Object[]> pairs = new ArrayList<>();
        for (Map.Entry<String, TreeMap<String, Integer>> e : votes1.entrySet()) {
            String cls = e.getKey();
            TreeMap<String, Integer> v = e.getValue();
            String top = guardedTop(v);
            if (top == null) {
                classesTieRejected++;
                continue;
            }
            pairs.add(new Object[] {v.get(top), cls, top});
        }
        // assign greedily: highest score first, deterministic tie-breaks by class then target
        pairs.sort(Comparator
                .comparingInt((Object[] p) -> -(Integer) p[0])
                .thenComparing(p -> (String) p[1])
                .thenComparing(p -> (String) p[2]));
        Set<String> takenMoj = new HashSet<>();
        for (Object[] p : pairs) {
            String cls = (String) p[1];
            String mcls = (String) p[2];
            if (obf13ToMojClass.containsKey(cls) || takenMoj.contains(mcls)) {
                classesBumpedOffTop++;
                continue;
            }
            obf13ToMojClass.put(cls, mcls);
            takenMoj.add(mcls);
        }

        // tier 2: full owner-set votes over untaken mojmap targets, same guard
        List<Object[]> cand = new ArrayList<>();
        for (String cls : obf13ClassOrder) {
            if (obf13ToMojClass.containsKey(cls) || obf13ClassSrgs.getOrDefault(cls, List.of()).isEmpty()) {
                continue;
            }
            TreeMap<String, Integer> v = new TreeMap<>();
            for (String srg : obf13ClassSrgs.get(cls)) {
                for (String m : srgOwnerSets.getOrDefault(srg, Set.of())) {
                    if (!takenMoj.contains(m)) {
                        v.merge(m, 1, Integer::sum);
                    }
                }
            }
            if (v.isEmpty()) {
                continue;
            }
            votedClasses.add(cls);
            String top = guardedTop(v);
            if (top == null) {
                classesTieRejected++;
                continue;
            }
            cand.add(new Object[] {v.get(top), cls, top});
        }
        cand.sort(Comparator
                .comparingInt((Object[] p) -> -(Integer) p[0])
                .thenComparing(p -> (String) p[1])
                .thenComparing(p -> (String) p[2]));
        for (Object[] p : cand) {
            String cls = (String) p[1];
            String mcls = (String) p[2];
            if (obf13ToMojClass.containsKey(cls) || takenMoj.contains(mcls)) {
                classesBumpedOffTop++;
                continue;
            }
            obf13ToMojClass.put(cls, mcls);
            takenMoj.add(mcls);
        }

        classesVoted = votedClasses.size();
    }

    /**
     * Returns the winning target only if there is a unique top vote-getter that beats the runner-up
     * by at least {@link #MIN_MARGIN}. Otherwise returns null (ambiguous, leave unmapped). Applied
     * to BOTH tiers (the spike guarded only tier 2, which let the LecternBlock false-positive
     * through in tier 1).
     */
    private static String guardedTop(TreeMap<String, Integer> votes) {
        String best = null;
        int bestScore = -1;
        int secondScore = -1;
        // TreeMap iteration is deterministic (sorted by target name), so ties resolve stably.
        for (Map.Entry<String, Integer> e : votes.entrySet()) {
            int s = e.getValue();
            if (s > bestScore) {
                secondScore = bestScore;
                bestScore = s;
                best = e.getKey();
            } else if (s > secondScore) {
                secondScore = s;
            }
        }
        if (best == null) {
            return null;
        }
        if (bestScore - secondScore < MIN_MARGIN) {
            return null; // tie or margin too small
        }
        return best;
    }

    // ============================================================================================
    // Structural cross-check: reject a class assignment on a definite structural contradiction
    // between the obf13 class and its candidate mojmap class (both projected into mojmap space).
    // ============================================================================================
    private void structuralCrossCheck() {
        Set<String> debug = Set.of(
                "net/minecraft/core/BlockPos", "net/minecraft/nbt/CompoundTag", "net/minecraft/nbt/ListTag",
                "net/minecraft/world/entity/Entity", "net/minecraft/world/level/block/state/BlockState",
                "net/minecraft/network/protocol/Packet", "net/minecraft/nbt/Tag",
                "net/minecraft/world/level/storage/LevelStorageSource");
        List<String> reject = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(obf13ToMojClass).entrySet()) {
            String c13 = e.getKey();
            String cMoj = e.getValue();
            boolean dbg = DEBUG && debug.contains(cMoj);
            if (!structAgrees(c13, cMoj, dbg)) {
                reject.add(c13);
            }
        }
        for (String c : reject) {
            obf13ToMojClass.remove(c);
            classesStructRejected++;
        }
    }

    /**
     * True unless there is a definite structural contradiction. We reject when superclass identity,
     * interface arity, or the field-descriptor multiset positively disagree in mojmap space. When a
     * signal cannot be determined (missing jar entry, unmapped supertype) we do not reject on it.
     */
    private boolean structAgrees(String c13, String cMoj, boolean dbg) {
        String obf14 = mojClassM2o.get(cMoj);
        ClassInfo i13 = jar13.get(c13);
        ClassInfo i14 = obf14 != null ? jar14.get(obf14) : null;
        if (i13 == null || i14 == null) {
            return true; // cannot determine -> do not reject
        }

        // Field-descriptor multiset overlap in mojmap space. Exact agreement is NOT usable here:
        // Minecraft classes are refactored across 1.13.2 -> 1.14.4 (superclasses inserted, fields
        // hoisted into new bases), so a correct match such as BlockState (Object -> AbstractStateHolder)
        // shares zero fields yet is the same class. We therefore veto only a gross structural
        // contradiction: two field-rich classes whose field-descriptor multisets are wholly disjoint,
        // which no genuine same-class pair exhibits. The vote margin guard and the intermediary oracle
        // are the primary correctness signals; this is a defense-in-depth backstop.
        TreeMap<String, Integer> f13 = fieldDescMulti(i13, obf13ToMojClass);
        TreeMap<String, Integer> f14 = fieldDescMulti(i14, obf14ToMoj);
        int common = 0;
        for (Map.Entry<String, Integer> en : f13.entrySet()) {
            common += Math.min(en.getValue(), f14.getOrDefault(en.getKey(), 0));
        }
        int minFields = Math.min(f13.size(), f14.size());
        boolean grossMismatch = common == 0 && minFields >= STRUCT_MIN_FIELDS;
        if (common == 0 && minFields >= 2) {
            classesStructSuspect++;
        }

        if (dbg) {
            String s13 = normClass(i13.superName, obf13ToMojClass);
            String s14 = normClass(i14.superName, obf14ToMoj);
            log("[struct-dbg] %s <- %s | super13=%s super14=%s | iface13=%d iface14=%d | fields13=%d fields14=%d common=%d | gross=%b",
                    cMoj, c13, s13, s14, i13.interfaces.size(), i14.interfaces.size(),
                    f13.size(), f14.size(), common, grossMismatch);
        }
        if (grossMismatch) {
            log("[struct-veto] %s <- %s (fields13=%d fields14=%d common=0)", cMoj, c13, f13.size(), f14.size());
        }
        return !grossMismatch;
    }

    /**
     * Field-disjointness threshold for the structural veto. Exact structural agreement (superclass
     * identity + interface arity + field multiset) is not achievable across 1.13.2 -&gt; 1.14.4 because
     * Minecraft classes are refactored between these versions (BlockState gains AbstractStateHolder,
     * ListTag gains CollectionTag, fields are hoisted into new bases), so a correct match can share
     * zero fields. The searge member-id vote with its per-tier margin guard is the primary identity
     * signal; this veto only rejects a bind where two field-rich classes share literally no field
     * descriptor, a contradiction no genuine same-class pair produces.
     */
    private static final int STRUCT_MIN_FIELDS = 4;

    /**
     * Projects an internal class name into mojmap space. For obf13 we use the composed class map;
     * for obf14 we use the ProGuard map. Java/library classes (no mapping) pass through unchanged so
     * both sides agree on them. Returns null only for null input.
     */
    private static String normClass(String name, Map<String, String> map) {
        if (name == null) {
            return null;
        }
        String mapped = map.get(name);
        return mapped != null ? mapped : name;
    }

    private static TreeMap<String, Integer> fieldDescMulti(ClassInfo info, Map<String, String> map) {
        TreeMap<String, Integer> multi = new TreeMap<>();
        for (String desc : info.fields.values()) {
            multi.merge(normDesc(desc, map), 1, Integer::sum);
        }
        return multi;
    }

    /** Normalizes a field/method descriptor's object types into mojmap space via the given map. */
    private static String normDesc(String desc, Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < desc.length()) {
            char c = desc.charAt(i);
            if (c == 'L') {
                int semi = desc.indexOf(';', i);
                String cls = desc.substring(i + 1, semi);
                String mapped = map.get(cls);
                sb.append('L').append(mapped != null ? mapped : cls).append(';');
                i = semi + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Returns true if every object type in the descriptor is either a primitive/array marker, a
     * java/library class, or an obf13 class that maps to mojmap. When a class ref is an obf13 name
     * with no mapping, the descriptor cannot be normalized (a churned 1.14-only type) -&gt; false.
     */
    private boolean descFullyNormalizable(String desc) {
        int i = 0;
        while (i < desc.length()) {
            char c = desc.charAt(i);
            if (c == 'L') {
                int semi = desc.indexOf(';', i);
                String cls = desc.substring(i + 1, semi);
                if (!obf13ToMojClass.containsKey(cls) && !cls.startsWith("java/")
                        && !cls.startsWith("javax/") && !cls.startsWith("com/google/")
                        && !cls.startsWith("com/mojang/") && !cls.startsWith("org/")
                        && !cls.startsWith("it/unimi/") && !cls.startsWith("io/netty/")) {
                    return false;
                }
                i = semi + 1;
            } else {
                i++;
            }
        }
        return true;
    }

    // ============================================================================================
    // Member mapping: srg pivot + descriptor-fallback for churned-srg members.
    // ============================================================================================
    private void mapMembers(Map<String, TreeMap<String, String[]>> methodsByClass,
            Map<String, TreeMap<String, String[]>> fieldsByClass) {
        // churned methods collected per mapped class for the fallback pass
        Map<String, List<String[]>> churnedByClass = new HashMap<>();
        // Every srg-mapped method's (normalized param descriptor -> mojmap name) per mapped class,
        // identity maps (mojmap name equal to the obf name, such as equals or toString) included.
        // An identity-named sibling emits no mapping line but must still occupy its descriptor slot,
        // otherwise the fallback could assign a churned method onto its name.
        Map<String, Map<String, Set<String>>> srgMappedByDesc = new HashMap<>();

        for (String[] m : obf13Methods) {
            String cls = m[0];
            String name = m[1];
            String desc = m[2];
            String srg = m[3];
            String moj = srgMethodToMoj.get(srg);
            if (moj != null) {
                if (!moj.equals(name)) {
                    methodsByClass.computeIfAbsent(cls, k -> new TreeMap<>())
                            .put(name + "\t" + desc, new String[] {desc, name, moj});
                }
                if (obf13ToMojClass.containsKey(cls)) {
                    srgMappedByDesc.computeIfAbsent(cls, k -> new HashMap<>())
                            .computeIfAbsent(normDesc(paramPart(desc), obf13ToMojClass), k -> new HashSet<>())
                            .add(moj);
                }
                membersSrgMapped++;
            } else if (obf13ToMojClass.containsKey(cls)) {
                churnedByClass.computeIfAbsent(cls, k -> new ArrayList<>()).add(m);
            }
        }

        // descriptor-fallback pass (methods)
        for (Map.Entry<String, List<String[]>> e : churnedByClass.entrySet()) {
            String cls = e.getKey();
            String cMoj = obf13ToMojClass.get(cls);
            Map<String, Set<String>> byDesc = mojMethodsByOwnerDesc.getOrDefault(cMoj, Map.of());

            // mojmap names already occupied by srg-mapped methods in this class (identity maps
            // included, seeded from the complete record above), keyed by normalized param descriptor,
            // so a churned method cannot be assigned onto a name a srg-mapped sibling already holds.
            Map<String, Set<String>> consumed = srgMappedByDesc.getOrDefault(cls, Map.of());

            // group churned methods by normalized parameter descriptor to detect intra-class ambiguity
            Map<String, List<String[]>> churnByDesc = new HashMap<>();
            for (String[] m : e.getValue()) {
                if (!descFullyNormalizable(paramPart(m[2]))) {
                    membersChurnedTypeUnmapped++;
                    membersLeftUnmapped++;
                    continue;
                }
                String nd = normDesc(paramPart(m[2]), obf13ToMojClass);
                churnByDesc.computeIfAbsent(nd, k -> new ArrayList<>()).add(m);
            }
            for (Map.Entry<String, List<String[]>> g : churnByDesc.entrySet()) {
                String nd = g.getKey();
                List<String[]> group = g.getValue();
                Set<String> candidates = new TreeSet<>(byDesc.getOrDefault(nd, Set.of()));
                candidates.removeAll(consumed.getOrDefault(nd, Set.of()));
                if (group.size() >= 2) {
                    // intra-class descriptor ambiguity: cannot tell which churned member is which
                    for (String[] m : group) {
                        membersLeftUnmapped++;
                    }
                    continue;
                }
                String[] m = group.get(0);
                if (candidates.size() == 1) {
                    String moj = candidates.iterator().next();
                    if (!moj.equals(m[1])) {
                        methodsByClass.computeIfAbsent(cls, k -> new TreeMap<>())
                                .put(m[1] + "\t" + m[2], new String[] {m[2], m[1], moj});
                    }
                    membersDescfallbackMapped++;
                } else {
                    membersLeftUnmapped++;
                }
            }
        }

        // fields: srg pivot only (field descriptors are rarely discriminating for a fallback)
        for (String[] f : obf13Fields) {
            String cls = f[0];
            String name = f[1];
            String srg = f[2];
            String moj = srgFieldToMoj.get(srg);
            if (moj == null) {
                if (obf13ToMojClass.containsKey(cls)) {
                    membersLeftUnmapped++;
                }
                continue;
            }
            membersSrgMapped++;
            if (moj.equals(name)) {
                continue;
            }
            ClassInfo info = jar13.get(cls);
            String desc = info != null ? info.fields.get(name) : null;
            if (desc == null) {
                continue; // no descriptor available -> cannot emit a tiny v2 field line
            }
            fieldsByClass.computeIfAbsent(cls, k -> new TreeMap<>())
                    .put(name + "\t" + desc, new String[] {desc, name, moj});
        }
    }

    // ============================================================================================
    // Member-propagation consistency over the merged hierarchy.
    //
    // Loom remaps the merged jar with tiny-remapper, which propagates each method mapping across the
    // class hierarchy: every class that declares a virtual method with the same (name, descriptor) is
    // one "slot" and must resolve to ONE target name, else tiny-remapper aborts with "Unfixable
    // conflicts" (the composed official->named mapping leaves some overrides at their Mojmap name and
    // others at their Legacy Fabric intermediary name, or assigns two Mojmap names to one slot).
    //
    // A slot is the set of classes declaring a given virtual (name, descriptor), grouped by the
    // inheritance graph: two declaring classes are in one slot when one is a transitive subtype of the
    // other (an override chain), computed by unioning each declaring class with every declaring
    // ancestor. This matches tiny-remapper's reachability (it walks up through all parents and down
    // through all children, turning direction only at a class that declares the method). For each slot:
    //   - exactly one Mojmap name M among its classes: assign M to every declaring class in the slot
    //     (adding the overrides the srg pivot left unmapped, which fall to intermediary otherwise);
    //   - two or more Mojmap names: the composition is genuinely divergent (distinct Mojmap methods
    //     sharing one obfuscated slot); leave the ENTIRE slot unmapped so all overrides fall to their
    //     consistent intermediary name, rather than emit a wrong unified name.
    // Only virtual methods propagate; fields and static/private methods are set only on their own class
    // and cannot produce a source-name conflict, so they are untouched.
    // ============================================================================================
    private void enforceHierarchyConsistency(Map<String, TreeMap<String, String[]>> methodsByClass) {
        Map<String, List<String>> declByslot = new HashMap<>();
        for (Map.Entry<String, MergedClass> e : merged.entrySet()) {
            for (String slot : e.getValue().virtualMethods) {
                declByslot.computeIfAbsent(slot, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        for (Map.Entry<String, List<String>> e : declByslot.entrySet()) {
            String slot = e.getKey();
            int tab = slot.indexOf('\t');
            String name = slot.substring(0, tab);
            String desc = slot.substring(tab + 1);
            List<String> classes = e.getValue();
            Set<String> declSet = new HashSet<>(classes);

            Map<String, String> parent = new HashMap<>();
            for (String c : classes) {
                parent.put(c, c);
            }
            for (String c : classes) {
                for (String anc : ancestors(c)) {
                    if (declSet.contains(anc)) {
                        ufUnion(parent, c, anc);
                    }
                }
            }
            Map<String, List<String>> components = new TreeMap<>();
            for (String c : classes) {
                components.computeIfAbsent(ufFind(parent, c), k -> new ArrayList<>()).add(c);
            }

            String memberKey = name + "\t" + desc;
            for (List<String> comp : components.values()) {
                Set<String> mojNames = new TreeSet<>();
                for (String c : comp) {
                    String[] m = methodsByClass.getOrDefault(c, EMPTY_METHODS).get(memberKey);
                    if (m != null) {
                        mojNames.add(m[2]);
                    }
                }
                if (mojNames.isEmpty()) {
                    continue;
                }
                if (mojNames.size() == 1) {
                    String moj = mojNames.iterator().next();
                    for (String c : comp) {
                        TreeMap<String, String[]> byMember = methodsByClass.get(c);
                        if (byMember == null || !byMember.containsKey(memberKey)) {
                            methodsByClass.computeIfAbsent(c, k -> new TreeMap<>())
                                    .put(memberKey, new String[] {desc, name, moj});
                            membersHierarchyPropagated++;
                        }
                    }
                } else {
                    hierarchyDivergentSlots++;
                    for (String c : comp) {
                        TreeMap<String, String[]> byMember = methodsByClass.get(c);
                        if (byMember != null && byMember.remove(memberKey) != null) {
                            membersHierarchyUnmapped++;
                        }
                    }
                }
            }
        }
    }

    private static final TreeMap<String, String[]> EMPTY_METHODS = new TreeMap<>();

    /** All transitive supertypes (superclass + interfaces) of a merged class. */
    private Iterable<String> ancestors(String c) {
        List<String> out = new ArrayList<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        MergedClass info = merged.get(c);
        if (info == null) {
            return out;
        }
        if (info.superName != null) {
            queue.add(info.superName);
        }
        queue.addAll(info.interfaces);
        while (!queue.isEmpty()) {
            String x = queue.poll();
            if (!seen.add(x)) {
                continue;
            }
            out.add(x);
            MergedClass xi = merged.get(x);
            if (xi != null) {
                if (xi.superName != null) {
                    queue.add(xi.superName);
                }
                queue.addAll(xi.interfaces);
            }
        }
        return out;
    }

    private static String ufFind(Map<String, String> parent, String x) {
        while (!parent.get(x).equals(x)) {
            parent.put(x, parent.get(parent.get(x)));
            x = parent.get(x);
        }
        return x;
    }

    private static void ufUnion(Map<String, String> parent, String a, String b) {
        String ra = ufFind(parent, a);
        String rb = ufFind(parent, b);
        if (!ra.equals(rb)) {
            parent.put(ra, rb);
        }
    }

    // ============================================================================================
    // Package-preserving fallback for unmapped classes.
    //
    // A composed class with no Mojmap name (a 1.13.2-only class, no surviving srg->mojmap) would be
    // emitted with an empty target. Loom's official->intermediary->named then names it by its flat
    // Legacy Fabric intermediary name net/minecraft/class_NNNN, in the ROOT net.minecraft package. That
    // relocation breaks vanilla's own same-package protected access: a real Block subclass stranded in
    // the net.minecraft package can no longer be constructed by Block, which lives in the deeper
    // net.minecraft.world.level.block package, so Bootstrap.bootStrap() dies with IllegalAccessError.
    //
    // Every unmapped top-level class instead gets a named target in the Mojmap package of the mapped
    // class it is same-package-coupled to. Coupling is: it (or one of its inner classes, which inherit
    // its package) is a subtype OR supertype of a mapped class, or accesses a mapped class's protected
    // or package-private member, or a mapped class accesses one of its own protected or package-private
    // members. A class the merged jar shows no such coupling for is not same-package-bound to any mapped
    // class, so its srg package (a stable non-root package, though the MCP layout, not the Mojmap one)
    // is a safe last resort. The simple name is the intermediary name (globally unique). Inner classes
    // inherit their outer's package through Loom's inner-class completion, so only top-level classes are
    // packaged here; numeric-anonymous inners still emit nothing.
    // ============================================================================================
    private void packageUnmappedClasses() {
        Set<String> reallyMapped = new HashSet<>(obf13ToMojClass.keySet());
        Set<String> usedTargets = new HashSet<>(obf13ToMojClass.values());
        for (String c : new TreeSet<>(obf13ClassToSrg.keySet())) {
            if (c.indexOf('$') >= 0 || obf13ToMojClass.containsKey(c)) {
                continue; // inner classes inherit via completion; mapped classes keep their Mojmap name
            }
            if (!landsInRootPackage(officialToInter.get(c))) {
                continue; // already in a real package (an already-deobfuscated class); no relocation
            }
            String couplingPackage = orphanCoupledPackage.get(c);
            String ancestorPackage = nearestMappedAncestorPackage(c, reallyMapped);
            String packageName;
            if (couplingPackage != null) {
                packageName = couplingPackage;
                fallbackPackagedByCoupling++;
            } else if (ancestorPackage != null) {
                packageName = ancestorPackage;
                fallbackPackagedByAncestor++;
            } else {
                packageName = srgPackage(c);
                fallbackPackagedBySrg++;
            }
            String simple = simpleName(officialToInter.getOrDefault(c, c));
            String base = packageName.isEmpty() ? simple : packageName + "/" + simple;
            String target = base;
            for (int n = 1; usedTargets.contains(target); n++) {
                target = base + "_" + n;
            }
            obf13ToMojClass.put(c, target);
            usedTargets.add(target);
            fallbackClasses.add(c);
        }
    }

    /** Mojmap package of the nearest mapped (superclass then interface) ancestor, or null if none. */
    private String nearestMappedAncestorPackage(String c, Set<String> reallyMapped) {
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        MergedClass info = merged.get(c);
        if (info == null) {
            return null;
        }
        if (info.superName != null) {
            queue.add(info.superName);
        }
        queue.addAll(info.interfaces);
        while (!queue.isEmpty()) {
            String x = queue.poll();
            if (!seen.add(x)) {
                continue;
            }
            if (reallyMapped.contains(x)) {
                return packageOf(obf13ToMojClass.get(x));
            }
            MergedClass xi = merged.get(x);
            if (xi != null) {
                if (xi.superName != null) {
                    queue.add(xi.superName);
                }
                queue.addAll(xi.interfaces);
            }
        }
        return null;
    }

    private String srgPackage(String c) {
        String srg = obf13ClassToSrg.get(c);
        return srg != null ? packageOf(srg) : "";
    }

    private static String packageOf(String internalName) {
        int i = internalName.lastIndexOf('/');
        return i >= 0 ? internalName.substring(0, i) : "";
    }

    private static String simpleName(String internalName) {
        int i = internalName.lastIndexOf('/');
        return i >= 0 ? internalName.substring(i + 1) : internalName;
    }

    /** True for a flat Legacy Fabric intermediary name in the root net.minecraft package. */
    private static boolean landsInRootPackage(String name) {
        return name != null && name.startsWith("net/minecraft/class_")
                && name.indexOf('/', "net/minecraft/".length()) < 0;
    }

    private void loadIntermediaryNames(Path intermediaryTiny) throws IOException {
        MemoryMappingTree lf = new MemoryMappingTree();
        MappingReader.read(intermediaryTiny, MappingFormat.TINY_2_FILE, lf);
        int interNs = lf.getNamespaceId("intermediary");
        for (MappingTree.ClassMapping c : lf.getClasses()) {
            String owner = c.getSrcName();
            String dst = c.getDstName(interNs);
            if (dst != null) {
                officialToInter.put(owner, dst);
            }
            for (MappingTree.MethodMapping m : c.getMethods()) {
                String mdst = m.getDstName(interNs);
                if (mdst != null) {
                    officialToInterMethod.put(owner + "\t" + m.getSrcName() + "\t" + m.getSrcDesc(), mdst);
                }
            }
            for (MappingTree.FieldMapping f : c.getFields()) {
                String fdst = f.getDstName(interNs);
                if (fdst != null) {
                    officialToInterField.put(owner + "\t" + f.getSrcName(), new String[] {fdst, f.getSrcDesc()});
                }
            }
        }
    }

    // ============================================================================================
    // Coupling detection. An orphan (a fallback class with no mapped ancestor) can still be
    // same-package-coupled to a mapped class: it or one of its inner classes is a supertype or subtype
    // of a mapped class, or accesses a mapped class's protected/package-private member, or has one of
    // its own protected/package-private members accessed by a mapped class. The obfuscated jar keeps
    // every class in the root package, so those accesses are same-package there; distributing the
    // classes into real packages must keep each coupled pair together or the runtime throws
    // IllegalAccessError at class-load or member-access. The orphan is placed in the Mojmap package of
    // the mapped class it couples to (the majority package when several apply).
    // ============================================================================================
    private void computeOrphanCoupledPackages(Path client, Path server) throws IOException {
        Set<String> reallyMapped = new HashSet<>(obf13ToMojClass.keySet());
        buildMcChildren();
        scanCouplingReferences(client, server);

        Set<String> orphanTops = new TreeSet<>();
        for (String c : obf13ClassToSrg.keySet()) {
            if (c.indexOf('$') < 0 && !reallyMapped.contains(c)
                    && landsInRootPackage(officialToInter.get(c))) {
                orphanTops.add(c);
            }
        }

        Map<String, List<String>> family = new HashMap<>();
        for (String c : obf13ClassToSrg.keySet()) {
            family.computeIfAbsent(topLevel(c), k -> new ArrayList<>()).add(c);
        }

        Map<String, TreeMap<String, Integer>> candidates = new HashMap<>();
        Map<String, String> union = new HashMap<>();
        for (String o : orphanTops) {
            candidates.put(o, new TreeMap<>());
            union.put(o, o);
        }
        // strong same-package constraints from the hierarchy: a class must share the package of any
        // package-private supertype it extends, and of any mapped subtype that extends it when it is
        // itself package-private (extending or loading a package-private class needs same-package). When
        // the coupled class is another orphan the constraint is recorded as a union instead.
        for (String o : orphanTops) {
            TreeMap<String, Integer> votes = candidates.get(o);
            for (String c : family.getOrDefault(o, List.of())) {
                for (String anc : ancestors(c)) {
                    if (!isPackagePrivateClass(anc)) {
                        continue;
                    }
                    if (reallyMapped.contains(anc)) {
                        votes.merge(packageOf(obf13ToMojClass.get(anc)), 1, Integer::sum);
                    } else if (orphanTops.contains(topLevel(anc))) {
                        ufUnion(union, o, topLevel(anc));
                    }
                }
                if (isPackagePrivateClass(c)) {
                    for (String desc : mappedDescendants(c, reallyMapped)) {
                        votes.merge(packageOf(obf13ToMojClass.get(desc)), 1, Integer::sum);
                    }
                }
            }
        }
        // strong same-package constraints from protected/package-private member access. A coupling to a
        // mapped class votes that class's package; a coupling between two orphans unions them.
        for (String[] edge : couplingEdges) {
            String xTop = topLevel(edge[0]);
            String dTop = topLevel(edge[1]);
            if (!orphanTops.contains(xTop) && !orphanTops.contains(dTop)) {
                continue;
            }
            String xMapped = resolveMappedClass(edge[0], reallyMapped);
            String dMapped = resolveMappedClass(edge[1], reallyMapped);
            if (orphanTops.contains(xTop) && dMapped != null) {
                candidates.get(xTop).merge(packageOf(obf13ToMojClass.get(dMapped)), 1, Integer::sum);
            }
            if (orphanTops.contains(dTop) && xMapped != null) {
                candidates.get(dTop).merge(packageOf(obf13ToMojClass.get(xMapped)), 1, Integer::sum);
            }
            if (orphanTops.contains(xTop) && orphanTops.contains(dTop)) {
                ufUnion(union, xTop, dTop);
            }
        }
        // pool each coupled group's votes so an orphan anchored only through a coupled orphan inherits
        // the same package.
        Map<String, TreeMap<String, Integer>> groupVotes = new HashMap<>();
        for (String o : orphanTops) {
            String root = ufFind(union, o);
            TreeMap<String, Integer> into = groupVotes.computeIfAbsent(root, k -> new TreeMap<>());
            candidates.get(o).forEach((p, n) -> into.merge(p, n, Integer::sum));
        }
        for (String o : orphanTops) {
            TreeMap<String, Integer> votes = groupVotes.get(ufFind(union, o));
            String best = null;
            int bestVotes = 0;
            for (Map.Entry<String, Integer> e : votes.entrySet()) {
                if (e.getValue() > bestVotes) {
                    bestVotes = e.getValue();
                    best = e.getKey();
                }
            }
            if (best != null) {
                orphanCoupledPackage.put(o, best);
            }
        }
    }

    private boolean isPackagePrivateClass(String c) {
        MergedClass info = merged.get(c);
        return info != null && (info.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0;
    }

    private void buildMcChildren() {
        for (Map.Entry<String, MergedClass> e : merged.entrySet()) {
            String c = e.getKey();
            if (!obf13ClassToSrg.containsKey(c)) {
                continue;
            }
            MergedClass info = e.getValue();
            if (info.superName != null && obf13ClassToSrg.containsKey(info.superName)) {
                mcChildren.computeIfAbsent(info.superName, k -> new ArrayList<>()).add(c);
            }
            for (String i : info.interfaces) {
                if (obf13ClassToSrg.containsKey(i)) {
                    mcChildren.computeIfAbsent(i, k -> new ArrayList<>()).add(c);
                }
            }
        }
    }

    /** Mapped Minecraft classes that are transitive subtypes of the given class. */
    private List<String> mappedDescendants(String c, Set<String> reallyMapped) {
        List<String> out = new ArrayList<>();
        ArrayDeque<String> queue = new ArrayDeque<>(mcChildren.getOrDefault(c, List.of()));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String x = queue.poll();
            if (!seen.add(x)) {
                continue;
            }
            if (reallyMapped.contains(x)) {
                out.add(x);
            }
            queue.addAll(mcChildren.getOrDefault(x, List.of()));
        }
        return out;
    }

    /**
     * Records non-public cross-class member-access edges between Minecraft classes that require
     * same-package placement: an access to a package-private member, or to a protected member from a
     * class that is not a subtype of the declaring class. Deduplicated per accessor/declaring pair.
     */
    private void scanCouplingReferences(Path client, Path server) throws IOException {
        Set<String> pairSeen = new HashSet<>();
        Set<String> entrySeen = new HashSet<>();
        scanCouplingReferences(client, pairSeen, entrySeen);
        scanCouplingReferences(server, pairSeen, entrySeen);
    }

    private void scanCouplingReferences(Path jar, Set<String> pairSeen, Set<String> entrySeen)
            throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String entryName = e.getName();
                if (!entryName.endsWith(".class") || !entrySeen.add(entryName)) {
                    continue;
                }
                ClassReader cr = new ClassReader(zis.readAllBytes());
                String accessor = cr.getClassName();
                if (!obf13ClassToSrg.containsKey(accessor)) {
                    continue; // Minecraft classes only
                }
                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                            String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitFieldInsn(int opcode, String owner, String fn, String fd) {
                                recordReference(accessor, owner, fn, fd, pairSeen);
                            }

                            @Override
                            public void visitMethodInsn(int opcode, String owner, String mn, String md,
                                    boolean isInterface) {
                                recordReference(accessor, owner, mn, md, pairSeen);
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
    }

    private void recordReference(String accessor, String owner, String name, String desc,
            Set<String> pairSeen) {
        if (owner.equals(accessor) || !obf13ClassToSrg.containsKey(owner)) {
            return;
        }
        String[] declared = findMcMember(owner, name, desc);
        if (declared == null) {
            return;
        }
        String declaring = declared[0];
        int access = Integer.parseInt(declared[1]);
        if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE)) != 0) {
            return; // public: any package; private: same-class only
        }
        boolean protectedMember = (access & Opcodes.ACC_PROTECTED) != 0;
        if (protectedMember && isSubtype(accessor, declaring)) {
            return; // protected access from a subtype is allowed across packages
        }
        if (pairSeen.add(accessor + "\t" + declaring)) {
            couplingEdges.add(new String[] {accessor, declaring});
        }
    }

    /** Resolves a member to its declaring Minecraft class and access flags, walking supertypes. */
    private String[] findMcMember(String owner, String name, String desc) {
        String key = name + "\t" + desc;
        MergedClass info = merged.get(owner);
        if (info != null && info.memberAccess != null) {
            Integer a = info.memberAccess.get(key);
            if (a != null) {
                return new String[] {owner, a.toString()};
            }
        }
        for (String anc : ancestors(owner)) {
            MergedClass ai = merged.get(anc);
            if (ai != null && ai.memberAccess != null) {
                Integer a = ai.memberAccess.get(key);
                if (a != null) {
                    return new String[] {anc, a.toString()};
                }
            }
        }
        return null;
    }

    private boolean isSubtype(String c, String supertype) {
        if (c.equals(supertype)) {
            return true;
        }
        for (String anc : ancestors(c)) {
            if (anc.equals(supertype)) {
                return true;
            }
        }
        return false;
    }

    private String resolveMappedClass(String c, Set<String> reallyMapped) {
        String r = c;
        while (r != null) {
            if (reallyMapped.contains(r)) {
                return r;
            }
            int i = r.lastIndexOf('$');
            r = i > 0 ? r.substring(0, i) : null;
        }
        return null;
    }

    private static String topLevel(String c) {
        int i = c.indexOf('$');
        return i < 0 ? c : c.substring(0, i);
    }

    // ============================================================================================
    // Coupling gate: after packaging, no cross-package protected/package-private coupling may remain
    // between a mapped class and a fallback class. This is the check the runtime boot exercises at
    // class-load (a package-private supertype in a different package) and member-access (a protected or
    // package-private member reached across a package boundary). It is build-failing and is what
    // actually predicts the boot, being stronger than the root-package check.
    // ============================================================================================
    private boolean couplingGate() {
        effCache.clear();
        List<String> violations = new ArrayList<>();
        for (String x : new TreeSet<>(merged.keySet())) {
            MergedClass xi = merged.get(x);
            if (!obf13ClassToSrg.containsKey(x)) {
                continue;
            }
            List<String> supertypes = new ArrayList<>();
            if (xi.superName != null) {
                supertypes.add(xi.superName);
            }
            supertypes.addAll(xi.interfaces);
            for (String y : supertypes) {
                MergedClass yi = merged.get(y);
                if (yi == null || !obf13ClassToSrg.containsKey(y)) {
                    continue;
                }
                boolean packagePrivate = (yi.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0;
                if (packagePrivate && !finalPackage(x).equals(finalPackage(y))
                        && (isFallbackFamily(x) || isFallbackFamily(y))) {
                    violations.add("supertype " + finalName(x) + " extends/implements " + finalName(y));
                }
            }
        }
        for (String[] edge : couplingEdges) {
            String x = edge[0];
            String d = edge[1];
            if (!finalPackage(x).equals(finalPackage(d)) && (isFallbackFamily(x) || isFallbackFamily(d))) {
                violations.add("member " + finalName(x) + " -> " + finalName(d));
            }
        }
        if (!violations.isEmpty()) {
            System.err.println("[coupling] FAILED: " + violations.size()
                    + " cross-package couplings between a mapped and a fallback class, e.g. "
                    + violations.subList(0, Math.min(12, violations.size())));
            return false;
        }
        log("[coupling] 0 cross-package protected/package-private couplings between mapped and fallback");
        return true;
    }

    private String finalPackage(String c) {
        return packageOf(completedTarget(c));
    }

    private String finalName(String c) {
        return completedTarget(c);
    }

    private boolean isFallbackFamily(String c) {
        String r = c;
        while (r != null) {
            if (obf13ToMojClass.containsKey(r)) {
                return fallbackClasses.contains(r);
            }
            int i = r.lastIndexOf('$');
            r = i > 0 ? r.substring(0, i) : null;
        }
        return false;
    }

    // ============================================================================================
    // Collision resolution. Loom completes the class map with ORDINAL-PRESERVING inner-class
    // inheritance: for an outer ot mapped to M, every inner ot$N in the jar is named M$N, and this
    // completion overrides identity self-maps, so a pin such as ot$10 -> ot$10 does not hold. Because
    // obf13 anonymous-inner numbering differs from Mojmap's, the vote's ordinal-swap row
    // ot$2 -> EntityDataSerializers$10 then collides with the completion's ot$10 -> EntityDataSerializers$10.
    // The fix is to stop competing with the completion: for a numeric-anonymous inner whose outer is
    // renamed, emit no class mapping at all and let Loom's ordinal completion name it, which is
    // injective by construction and harmless because common never references anonymous inners by name.
    // Named inners ($Key, $Builder) and top-level classes keep their explicit mapping; the one
    // remaining real clash is the Realms passthrough (chr's explicit RealmsGuiEventListener versus the
    // unobfuscated net/minecraft/realms/RealmsGuiEventListener), resolved by blanking the intruder.
    // ============================================================================================
    private final Map<String, String> effCache = new HashMap<>();

    private Set<String> resolveCollisions(Set<String> mergedClasses) {
        Set<String> candidates = new TreeSet<>(mergedClasses);
        candidates.addAll(obf13ToMojClass.keySet());
        for (String c : candidates) {
            if (isNumericAnonWithRenamedAnchor(c)) {
                excludedAnons.add(c);
            }
        }
        obf13ToMojClass.keySet().removeAll(excludedAnons);

        effCache.clear();
        Map<String, List<String>> byEff = new TreeMap<>();
        for (String c : mergedClasses) {
            byEff.computeIfAbsent(completedTarget(c), k -> new ArrayList<>()).add(c);
        }
        Set<String> blanked = new TreeSet<>();
        for (Map.Entry<String, List<String>> e : byEff.entrySet()) {
            List<String> group = e.getValue();
            if (group.size() < 2) {
                continue;
            }
            String eff = e.getKey();
            group.sort(Comparator.naturalOrder());
            // Keep the real class whose own name already equals the target (the passthrough), else the
            // explicitly-mapped one; blank the rest so each passes through as its own name.
            String keeper = null;
            for (String c : group) {
                if (c.equals(eff)) {
                    keeper = c;
                    break;
                }
            }
            if (keeper == null) {
                for (String c : group) {
                    if (eff.equals(obf13ToMojClass.get(c))) {
                        keeper = c;
                        break;
                    }
                }
            }
            if (keeper == null) {
                keeper = group.get(0);
            }
            for (String c : group) {
                if (!c.equals(keeper) && obf13ToMojClass.remove(c) != null) {
                    blanked.add(c);
                    collisionsResolved++;
                }
            }
        }
        return blanked;
    }

    /**
     * The target Loom's completed class map resolves for an obf class: a real (non-identity) explicit
     * mapping, else the ordinal-preserving inheritance from a renamed outer, else the obf name. An
     * identity self-map is treated as unmapped, because Loom's ordinal completion overrides it.
     */
    private String completedTarget(String c) {
        String cached = effCache.get(c);
        if (cached != null) {
            return cached;
        }
        String result;
        String t = obf13ToMojClass.get(c);
        if (t != null && !t.equals(c)) {
            result = t;
        } else {
            int i = c.lastIndexOf('$');
            String outer = i > 0 ? completedTarget(c.substring(0, i)) : null;
            result = outer != null && !outer.equals(c.substring(0, i)) ? outer + c.substring(i) : c;
        }
        effCache.put(c, result);
        return result;
    }

    /** A class whose name after the last {@code $} is all digits (an anonymous inner). */
    private static boolean isNumericAnon(String c) {
        int i = c.lastIndexOf('$');
        if (i <= 0 || i == c.length() - 1) {
            return false;
        }
        for (int j = i + 1; j < c.length(); j++) {
            if (!Character.isDigit(c.charAt(j))) {
                return false;
            }
        }
        return true;
    }

    /**
     * True for a numeric-anonymous inner whose anchor (the class reached by stripping every trailing
     * numeric segment) carries a real, non-identity mapping. Such inners are dropped so ordinal
     * completion names them uniquely.
     */
    private boolean isNumericAnonWithRenamedAnchor(String c) {
        if (!isNumericAnon(c)) {
            return false;
        }
        String anchor = c;
        while (isNumericAnon(anchor)) {
            anchor = anchor.substring(0, anchor.lastIndexOf('$'));
        }
        String t = obf13ToMojClass.get(anchor);
        return t != null && !t.equals(anchor);
    }

    // ============================================================================================
    // Emit obf-mojmap.tiny (hand-written, deterministic sorted).
    // ============================================================================================
    private void emitObfMojmap(Path out, Map<String, TreeMap<String, String[]>> methodsByClass,
            Map<String, TreeMap<String, String[]>> fieldsByClass) throws IOException {
        // A numeric-anonymous inner stays out of the class map (blank target below, so Loom ordinal-
        // completes its name) but is still emitted when the hierarchy pass gave it an override mapping,
        // so its member line survives; an anon with no member mapping is absent from every source map
        // and so is not emitted at all.
        Set<String> emit = new TreeSet<>();
        emit.addAll(obf13ToMojClass.keySet());
        emit.addAll(methodsByClass.keySet());
        emit.addAll(fieldsByClass.keySet());

        StringBuilder sb = new StringBuilder();
        sb.append("tiny\t2\t0\tofficial\tmojmap\n");
        for (String cls : emit) {
            String target = obf13ToMojClass.getOrDefault(cls, "");
            sb.append("c\t").append(cls).append('\t').append(target).append('\n');
            for (String[] m : methodsByClass.getOrDefault(cls, new TreeMap<>()).values()) {
                sb.append("\tm\t").append(m[0]).append('\t').append(m[1]).append('\t').append(m[2]).append('\n');
            }
            for (String[] f : fieldsByClass.getOrDefault(cls, new TreeMap<>()).values()) {
                sb.append("\tf\t").append(f[0]).append('\t').append(f[1]).append('\t').append(f[2]).append('\n');
            }
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    // ============================================================================================
    // Derive intermediary -> named (via mapping-io) and package the jar.
    // ============================================================================================
    private void deriveIntermediaryNamed(MemoryMappingTree oneMap, Path intermediaryTiny,
            Path outTiny, Path outJar) throws IOException {
        MemoryMappingTree tree = new MemoryMappingTree();
        MappingReader.read(intermediaryTiny, MappingFormat.TINY_2_FILE, tree); // official -> intermediary
        oneMap.accept(tree); // merge official -> mojmap on official

        MappingWriter writer = MappingWriter.create(outTiny, MappingFormat.TINY_2_FILE);
        tree.accept(new MappingSourceNsSwitch(
                new MappingNsRenamer(
                        new MappingDstNsReorder(writer, List.of("named")),
                        Map.of("mojmap", "named")),
                "intermediary"));
        writer.close();

        packageJar(outTiny, outJar);
    }

    // ============================================================================================
    // Derive mojmap -> srg (hand-built from the one map + joined_1132 srg ids).
    //
    // The source column must equal the name Loom leaves on the dev classpath for EVERY class and member,
    // not only the ones the bridge Mojmap-maps: where a symbol stays unmapped the classpath keeps its
    // Legacy Fabric intermediary name (class_NNNN / field_NNN / method_NNNNN), so the seam port that
    // references it needs those intermediary names to reobfuscate too, or the shipped jar throws
    // NoSuchField/MethodError at load. So every obf class with a 1.13.2 srg is emitted (mapped, fallback,
    // OR an unmapped inner Loom completes as mappedOuter$intermediarySimple), and within it every member
    // is emitted with its Mojmap name where the bridge mapped it, else its intermediary name.
    //
    // Field lines in tsrg v1 have no descriptor, so mapping-io cannot merge them by (name,desc); building
    // the map directly from the in-memory maps is exact and deterministic.
    // ============================================================================================
    private void deriveMojmapSrg(Path out, Map<String, TreeMap<String, String[]>> methodsByClass,
            Map<String, TreeMap<String, String[]>> fieldsByClass, Set<String> blanked) throws IOException {
        // obf13 member -> srg lookup
        Map<String, String> methodSrg = new HashMap<>();
        for (String[] m : obf13Methods) {
            methodSrg.put(m[0] + "\t" + m[1] + "\t" + m[2], m[3]);
        }
        Map<String, String> fieldSrg = new HashMap<>();
        for (String[] f : obf13Fields) {
            fieldSrg.put(f[0] + "\t" + f[1], f[2]);
        }
        // obf13 members grouped by owner, so the intermediary remainder pass can enumerate a class's own
        // members without rescanning the flat lists per class.
        Map<String, List<String[]>> methodsByOwner = new HashMap<>();
        for (String[] m : obf13Methods) {
            methodsByOwner.computeIfAbsent(m[0], k -> new ArrayList<>()).add(m);
        }
        Map<String, List<String[]>> fieldsByOwner = new HashMap<>();
        for (String[] f : obf13Fields) {
            fieldsByOwner.computeIfAbsent(f[0], k -> new ArrayList<>()).add(f);
        }

        // The dev-classpath name of every obf13 class, i.e. Loom's completed named result. Descriptors are
        // normalized through this (not obf13ToMojClass), so a member whose type is an unmapped inner still
        // reobfuscates by a descriptor that matches the compiled bytecode.
        Map<String, String> devClass = new HashMap<>();
        for (String c : obf13ClassToSrg.keySet()) {
            devClassName(c, devClass);
        }

        // one row per dev class (source): srg class name + members (tag, devDesc, devName, srg)
        TreeMap<String, String> classSrg = new TreeMap<>();
        Map<String, List<String[]>> membersBySrc = new HashMap<>();
        for (String c13 : new TreeSet<>(obf13ClassToSrg.keySet())) {
            // A numeric-anonymous inner emits no class line: Loom ordinal-completes its name and common
            // never references it by name. A class the collision pass blanked emits none ONLY when it was
            // not then re-packaged; packageUnmappedClasses gives every blanked class that would otherwise
            // strand in the root package a fallback name (recorded in fallbackClasses) and Loom names it by
            // that fallback, so a symbol that references it as a supertype needs its class_NNNN -> srg line
            // (class_4122 = IGuiEventListener, implemented by the screens, is the leak that omitting these
            // produced). Emit the re-packaged ones under their fallback dev name.
            if (excludedAnons.contains(c13) || (blanked.contains(c13) && !fallbackClasses.contains(c13))) {
                continue;
            }
            String srgClass = obf13ClassToSrg.get(c13);
            String src = devClass.get(c13);
            classSrg.put(src, srgClass);
            List<String[]> members = membersBySrc.computeIfAbsent(src, k -> new ArrayList<>());

            // Members the bridge Mojmap-mapped: emit the Mojmap name, and record the obf identity so the
            // remainder pass does not re-emit them under their intermediary name.
            Set<String> mappedMethods = new HashSet<>();
            Set<String> mappedFields = new HashSet<>();
            for (String[] mm : methodsByClass.getOrDefault(c13, new TreeMap<>()).values()) {
                mappedMethods.add(mm[1] + "\t" + mm[0]);
                String srg = methodSrg.get(c13 + "\t" + mm[1] + "\t" + mm[0]);
                if (srg != null) {
                    members.add(new String[] {"m", normDesc(mm[0], devClass), mm[2], srg});
                }
            }
            for (String[] ff : fieldsByClass.getOrDefault(c13, new TreeMap<>()).values()) {
                mappedFields.add(ff[1]);
                String srg = fieldSrg.get(c13 + "\t" + ff[1]);
                if (srg != null) {
                    members.add(new String[] {"f", normDesc(ff[0], devClass), ff[2], srg});
                }
            }

            // Intermediary remainder: every remaining member keeps its Legacy Fabric intermediary name on
            // the dev classpath (or, absent one, its obf name), so emit that name -> srg.
            for (String[] m : methodsByOwner.getOrDefault(c13, List.of())) {
                if (mappedMethods.contains(m[1] + "\t" + m[2])) {
                    continue;
                }
                String inter = officialToInterMethod.get(c13 + "\t" + m[1] + "\t" + m[2]);
                members.add(new String[] {"m", normDesc(m[2], devClass), inter != null ? inter : m[1], m[3]});
            }
            for (String[] f : fieldsByOwner.getOrDefault(c13, List.of())) {
                if (mappedFields.contains(f[1])) {
                    continue;
                }
                String[] inter = officialToInterField.get(c13 + "\t" + f[1]);
                String name = inter != null ? inter[0] : f[1];
                String obfDesc = inter != null ? inter[1] : fieldDesc(c13, f[1]);
                if (obfDesc == null) {
                    continue; // no descriptor available -> cannot emit a tiny v2 field line
                }
                members.add(new String[] {"f", normDesc(obfDesc, devClass), name, f[2]});
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("tiny\t2\t0\tmojmap\tsrg\n");
        for (Map.Entry<String, String> e : classSrg.entrySet()) {
            String src = e.getKey();
            sb.append("c\t").append(src).append('\t').append(e.getValue()).append('\n');
            List<String[]> members = membersBySrc.getOrDefault(src, List.of());
            members.sort(Comparator.<String[], String>comparing(r -> r[0])
                    .thenComparing(r -> r[2]).thenComparing(r -> r[1]).thenComparing(r -> r[3]));
            for (String[] row : members) {
                sb.append('\t').append(row[0]).append('\t').append(row[1]).append('\t')
                        .append(row[2]).append('\t').append(row[3]).append('\n');
            }
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * The dev-classpath (Loom named) internal name of an obf13 class, memoized into {@code cache}: the
     * Mojmap or package-preserving fallback name where the bridge assigned one, else Loom's inner-class
     * completion (the mapped outer's name plus {@code $} plus the intermediary simple name), else the
     * intermediary name of a top-level class the bridge left blank. This is what tiny-remapper wrote onto
     * the provisioned classpath, so it is the exact source-side name the reobf mapping must key on.
     */
    private String devClassName(String c, Map<String, String> cache) {
        String cached = cache.get(c);
        if (cached != null) {
            return cached;
        }
        String result;
        String mapped = obf13ToMojClass.get(c);
        if (mapped != null && !mapped.equals(c)) {
            result = mapped;
        } else {
            int i = c.lastIndexOf('$');
            if (i > 0) {
                String inter = officialToInter.get(c);
                String innerSimple = inter != null ? inter.substring(inter.lastIndexOf('$') + 1) : c.substring(i + 1);
                result = devClassName(c.substring(0, i), cache) + "$" + innerSimple;
            } else {
                String inter = officialToInter.get(c);
                result = inter != null ? inter : c;
            }
        }
        cache.put(c, result);
        return result;
    }

    /** Obf field descriptor from the client jar structural read, or null for a server-only field. */
    private String fieldDesc(String owner, String name) {
        ClassInfo info = jar13.get(owner);
        return info != null ? info.fields.get(name) : null;
    }

    // ============================================================================================
    // Deterministic jar packaging (fixed timestamps, fixed manifest).
    // ============================================================================================
    private void packageJar(Path tiny, Path jar) throws IOException {
        byte[] manifest = "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] mappings = Files.readAllBytes(tiny);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            writeEntry(zos, "META-INF/MANIFEST.MF", manifest);
            writeEntry(zos, "mappings/mappings.tiny", mappings);
        }
    }

    private static void writeEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(0L); // fixed timestamp for determinism
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    // ============================================================================================
    // Remap-apply gate: feed tiny-remapper the FULL official->named mapping Loom builds when it remaps
    // the merged jar, and apply it over the merged client+server jar. It composes exactly as Loom does:
    //   - classes: Loom's ordinal-preserving inner-class completion (completedTarget), which overrides
    //     the identity self-map of an unmapped anonymous inner with mapped-outer$N;
    //   - members: our Mojmap name where we have one, else the Legacy Fabric intermediary name (the
    //     named column defaults to intermediary), so an override the srg pivot missed carries its
    //     method_NNNNN name rather than staying obfuscated.
    // tiny-remapper then runs member propagation with its defaults (the same the plain builder gives
    // Loom): a class-target collision throws "duplicate class target name mappings detected" and a slot
    // that propagates two different names throws "Unfixable conflicts". Feeding classes AND members with
    // propagation on is the whole point; a class-only gate went green here twice while Loom failed on
    // member propagation. Disabling the hierarchy pass (BRIDGE_SKIP_HIERARCHY_FIX) makes this gate red
    // with the exact 479-conflict failure a fresh provision hits, proving it reproduces Loom.
    // ============================================================================================
    private boolean remapApplyGate(Set<String> mergedClasses, Path client, Path server, Path intermediaryTiny,
            Map<String, TreeMap<String, String[]>> methodsByClass,
            Map<String, TreeMap<String, String[]>> fieldsByClass) {
        Path work = null;
        try {
            // Throwaway jars go to the default temp directory, not build/: on WSL the build tree sits
            // on a slow 9p Windows mount where writing tens of megabytes stalls the gate for minutes.
            work = Files.createTempDirectory("mojmap-bridge-remap");
            Path merged = work.resolve("merged_1132.jar");
            mergeJars(client, server, merged);

            MemoryMappingTree lf = new MemoryMappingTree();
            MappingReader.read(intermediaryTiny, MappingFormat.TINY_2_FILE, lf);
            int interNs = lf.getNamespaceId("intermediary");
            Map<String, String> interMethod = new TreeMap<>();
            Map<String, String> interField = new TreeMap<>();
            for (MappingTree.ClassMapping c : lf.getClasses()) {
                String owner = c.getSrcName();
                for (MappingTree.MethodMapping m : c.getMethods()) {
                    String dst = m.getDstName(interNs);
                    interMethod.put(owner + "\t" + m.getSrcName() + "\t" + m.getSrcDesc(),
                            dst != null ? dst : m.getSrcName());
                }
                for (MappingTree.FieldMapping f : c.getFields()) {
                    String dst = f.getDstName(interNs);
                    interField.put(owner + "\t" + f.getSrcName() + "\t" + f.getSrcDesc(),
                            dst != null ? dst : f.getSrcName());
                }
            }

            Map<String, String> ourMethod = new TreeMap<>();
            for (Map.Entry<String, TreeMap<String, String[]>> e : methodsByClass.entrySet()) {
                for (String[] m : e.getValue().values()) {
                    ourMethod.put(e.getKey() + "\t" + m[1] + "\t" + m[0], m[2]);
                }
            }
            Map<String, String> ourField = new TreeMap<>();
            for (Map.Entry<String, TreeMap<String, String[]>> e : fieldsByClass.entrySet()) {
                for (String[] f : e.getValue().values()) {
                    ourField.put(e.getKey() + "\t" + f[1] + "\t" + f[0], f[2]);
                }
            }

            Set<String> methodKeys = new TreeSet<>(interMethod.keySet());
            methodKeys.addAll(ourMethod.keySet());
            Set<String> fieldKeys = new TreeSet<>(interField.keySet());
            fieldKeys.addAll(ourField.keySet());

            effCache.clear();

            // Every class must land in a real package: Loom names an unmapped class by its flat
            // intermediary name in the root net.minecraft package, which the fallback packaging removes.
            List<String> rootStranded = new ArrayList<>();
            for (String c : mergedClasses) {
                String target = completedTarget(c);
                String loomName = target.equals(c) ? officialToInter.getOrDefault(c, c) : target;
                if (landsInRootPackage(loomName)) {
                    rootStranded.add(c + " -> " + loomName);
                }
            }
            if (!rootStranded.isEmpty()) {
                System.err.println("[remap] FAILED: " + rootStranded.size()
                        + " classes stranded in the root net.minecraft package, e.g. "
                        + rootStranded.subList(0, Math.min(10, rootStranded.size())));
                return false;
            }
            log("[remap] root-package check: 0 classes stranded in net.minecraft");

            int[] classRenames = {0};
            IMappingProvider provider = acceptor -> {
                for (String c : new TreeSet<>(mergedClasses)) {
                    String target = completedTarget(c);
                    if (!target.equals(c)) {
                        acceptor.acceptClass(c, target);
                        classRenames[0]++;
                    }
                }
                for (String key : methodKeys) {
                    int t1 = key.indexOf('\t');
                    int t2 = key.indexOf('\t', t1 + 1);
                    String owner = key.substring(0, t1);
                    String name = key.substring(t1 + 1, t2);
                    String desc = key.substring(t2 + 1);
                    String target = ourMethod.getOrDefault(key, interMethod.getOrDefault(key, name));
                    acceptor.acceptMethod(new IMappingProvider.Member(owner, name, desc), target);
                }
                for (String key : fieldKeys) {
                    int t1 = key.indexOf('\t');
                    int t2 = key.indexOf('\t', t1 + 1);
                    String owner = key.substring(0, t1);
                    String name = key.substring(t1 + 1, t2);
                    String desc = key.substring(t2 + 1);
                    String target = ourField.getOrDefault(key, interField.getOrDefault(key, name));
                    acceptor.acceptField(new IMappingProvider.Member(owner, name, desc), target);
                }
            };

            Path namedJar = work.resolve("remap_named.jar");
            Files.deleteIfExists(namedJar);
            TinyRemapper remapper = TinyRemapper.newRemapper().withMappings(provider).build();
            try (OutputConsumerPath consumer = new OutputConsumerPath.Builder(namedJar).build()) {
                remapper.readInputs(merged);
                remapper.apply(consumer);
            } finally {
                remapper.finish();
            }

            log("[remap] full official->named apply over merged jar: OK (%d class renames, %d methods, "
                    + "%d fields, %d classes, %d bytes)",
                    classRenames[0], methodKeys.size(), fieldKeys.size(), countClassEntries(namedJar),
                    Files.size(namedJar));
            return true;
        } catch (Throwable t) {
            System.err.println("[remap] FAILED: tiny-remapper rejected the full official->named mapping on the merged jar");
            for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
                System.err.println("   caused by: " + c);
            }
            return false;
        } finally {
            deleteRecursively(work);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort cleanup of a temp directory
                }
            });
        } catch (IOException ignored) {
            // best effort cleanup of a temp directory
        }
    }

    /** Loom-style merge: every client class, plus every server class the client does not already carry. */
    private static void mergeJars(Path client, Path server, Path out) throws IOException {
        Files.deleteIfExists(out);
        Set<String> seen = new HashSet<>();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {
            copyClassEntries(client, zos, seen);
            copyClassEntries(server, zos, seen);
        }
    }

    private static void copyClassEntries(Path jar, ZipOutputStream zos, Set<String> seen) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName();
                if (!n.endsWith(".class") || !seen.add(n)) {
                    continue;
                }
                writeEntry(zos, n, zis.readAllBytes());
            }
        }
    }

    private static long countClassEntries(Path jar) throws IOException {
        long n = 0;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().endsWith(".class")) {
                    n++;
                }
            }
        }
        return n;
    }

    // ============================================================================================
    // Oracle gate: every composed obf->mojmap class must agree with the LF intermediary on the
    // official obf identity (independent cross-check). Build-failing.
    // ============================================================================================
    private boolean oracleGate(Path intermediaryTiny) throws IOException {
        MemoryMappingTree it = new MemoryMappingTree();
        MappingReader.read(intermediaryTiny, MappingFormat.TINY_2_FILE, it);
        Set<String> interOfficial = new HashSet<>();
        for (MappingTree.ClassMapping c : it.getClasses()) {
            interOfficial.add(c.getSrcName());
        }
        int missing = 0;
        List<String> samples = new ArrayList<>();
        for (String off : new TreeSet<>(obf13ToMojClass.keySet())) {
            if (!interOfficial.contains(off)) {
                missing++;
                if (samples.size() < 10) {
                    samples.add(off);
                }
            }
        }
        int checked = obf13ToMojClass.size();
        log("[oracle] checked=%d agree=%d disagree=%d", checked, checked - missing, missing);
        if (missing > 0) {
            System.err.println("[oracle] FAILED: composed classes not present in LF intermediary official ns: " + samples);
            return false;
        }
        return true;
    }

    // ============================================================================================
    // Self-validation against ground truth.
    // ============================================================================================
    private boolean selfValidate(Map<String, TreeMap<String, String[]>> methodsByClass,
            Map<String, TreeMap<String, String[]>> fieldsByClass, Set<String> blanked) {
        boolean ok = true;

        Set<String> mojTargets = new HashSet<>();
        for (Map.Entry<String, String> e : obf13ToMojClass.entrySet()) {
            if (!blanked.contains(e.getKey()) && !excludedAnons.contains(e.getKey())) {
                mojTargets.add(e.getValue());
            }
        }

        String[] present = {
            "net/minecraft/core/BlockPos", "net/minecraft/nbt/CompoundTag", "net/minecraft/nbt/ListTag",
            "net/minecraft/world/entity/Entity", "net/minecraft/world/level/block/state/BlockState",
            "net/minecraft/network/protocol/Packet",
        };
        for (String c : present) {
            if (!mojTargets.contains(c)) {
                System.err.println("[validate] MISSING expected-present mojmap class: " + c);
                ok = false;
            }
        }

        String[] absent = {
            "net/minecraft/core/SectionPos", "net/minecraft/world/level/block/LecternBlock",
            "net/minecraft/world/level/lighting/LevelLightEngine",
        };
        for (String c : absent) {
            if (mojTargets.contains(c)) {
                System.err.println("[validate] UNEXPECTED-present mojmap class (should be absent): " + c);
                ok = false;
            }
        }

        // churned members recovered by descriptor-fallback
        ok &= checkMember(methodsByClass, "net/minecraft/nbt/CompoundTag",
                "(Ljava/lang/String;Lnet/minecraft/nbt/Tag;)V", "put");
        ok &= checkMember(methodsByClass, "net/minecraft/world/level/storage/LevelStorageSource",
                null, "selectLevel");

        return ok;
    }

    /**
     * Verifies a member resolves to the expected mojmap name. The obf13 owner is found via the
     * class map; when normDesc is given the member is matched by normalized descriptor, else by
     * mojmap name presence.
     */
    private boolean checkMember(Map<String, TreeMap<String, String[]>> methodsByClass,
            String mojClass, String wantNormDesc, String wantName) {
        String obf13Owner = null;
        for (Map.Entry<String, String> e : obf13ToMojClass.entrySet()) {
            if (e.getValue().equals(mojClass)) {
                obf13Owner = e.getKey();
                break;
            }
        }
        if (obf13Owner == null) {
            System.err.println("[validate] no obf13 owner for " + mojClass);
            return false;
        }
        for (String[] m : methodsByClass.getOrDefault(obf13Owner, new TreeMap<>()).values()) {
            if (!m[2].equals(wantName)) {
                continue;
            }
            if (wantNormDesc == null || normDesc(m[0], obf13ToMojClass).equals(wantNormDesc)) {
                log("[validate] %s.%s%s -> OK (obf13 %s.%s%s)", mojClass, wantName,
                        wantNormDesc == null ? "" : wantNormDesc, obf13Owner, m[1], m[0]);
                return true;
            }
        }
        System.err.println("[validate] member NOT resolved: " + mojClass + "." + wantName
                + (wantNormDesc == null ? "" : wantNormDesc));
        return false;
    }

    // ============================================================================================
    // Intermediary-remainder gate: three members the bridge leaves at their Legacy Fabric intermediary
    // name must resolve to their 1.13.2 srg id in the emitted mojmap-srg. One representative of each shape
    // the emit has to cover: an intermediary FIELD on a Mojmap-mapped class (HitResult.field_595, the shape
    // a class-name-only reobf would strand), an intermediary METHOD on a package-preserving fallback class
    // (net/minecraft/world/chunk/storage/class_1199.method_17185), and any member of an unmapped INNER
    // class Loom completes (net/minecraft/world/level/GameRules$class_1440.method_4675). Reads the emitted
    // file so it proves the artifact, not just the in-memory maps.
    // ============================================================================================
    private boolean intermediaryRemainderGate(Path mojmapSrg) throws IOException {
        String[][] want = {
            {"net/minecraft/world/phys/HitResult", "field_595", "field_72313_a"},
            {"net/minecraft/world/chunk/storage/class_1199", "method_17185", "func_75816_a"},
            {"net/minecraft/world/level/GameRules$class_1440", "method_4675", "func_82756_a"},
        };
        Map<String, String> emitted = new HashMap<>();
        String curClass = null;
        for (String line : Files.readAllLines(mojmapSrg, StandardCharsets.UTF_8)) {
            if (line.startsWith("c\t")) {
                String[] p = line.split("\t", -1);
                curClass = p.length >= 2 ? p[1] : null;
            } else if (line.startsWith("\t") && curClass != null) {
                String[] p = line.split("\t", -1);
                if (p.length >= 5 && (p[1].equals("m") || p[1].equals("f"))) {
                    emitted.put(curClass + "\t" + p[3], p[4]);
                }
            }
        }
        boolean ok = true;
        for (String[] w : want) {
            String got = emitted.get(w[0] + "\t" + w[1]);
            if (got == null || !got.equals(w[2])) {
                System.err.println("[remainder] FAILED: " + w[0] + "." + w[1]
                        + " expected srg " + w[2] + " but got " + got);
                ok = false;
            } else {
                log("[remainder] %s.%s -> %s OK", w[0], w[1], got);
            }
        }
        return ok;
    }

    // ============================================================================================
    // Class-remainder gate: a package-preserving fallback class the vote blanked on a collision and then
    // re-packaged must still emit its own c line, or a symbol that references it only as a type (not a
    // member call) strands its class_NNNN name in the reobf'd jar with no mapping and NoClassDefFounds at
    // load. The representative is class_4122 (obf chr) -> srg IGuiEventListener, the GuiEventListener
    // supertype the screens implement, which Task 7a surfaced leaking. Reads the emitted file so it proves
    // the artifact, not just the in-memory maps.
    // ============================================================================================
    private boolean classRemainderGate(Path mojmapSrg) throws IOException {
        String[][] want = {
            {"net/minecraft/client/gui/class_4122", "net/minecraft/client/gui/IGuiEventListener"},
        };
        Map<String, String> classes = new HashMap<>();
        for (String line : Files.readAllLines(mojmapSrg, StandardCharsets.UTF_8)) {
            if (line.startsWith("c\t")) {
                String[] p = line.split("\t", -1);
                if (p.length >= 3) {
                    classes.put(p[1], p[2]);
                }
            }
        }
        boolean ok = true;
        for (String[] w : want) {
            String got = classes.get(w[0]);
            if (got == null || !got.equals(w[1])) {
                System.err.println("[class-remainder] FAILED: " + w[0]
                        + " expected srg " + w[1] + " but got " + got);
                ok = false;
            } else {
                log("[class-remainder] %s -> %s OK", w[0], got);
            }
        }
        return ok;
    }

    private void printCounters() {
        System.out.println("=== COUNTERS ===");
        System.out.println("classes_voted=" + classesVoted);
        System.out.println("classes_assigned=" + classesAssigned);
        System.out.println("classes_bumped_off_top=" + classesBumpedOffTop);
        System.out.println("classes_tie_rejected=" + classesTieRejected);
        System.out.println("classes_struct_rejected=" + classesStructRejected);
        System.out.println("classes_struct_suspect=" + classesStructSuspect);
        System.out.println("classes_left_unmapped=" + classesLeftUnmapped);
        System.out.println("collisions_resolved=" + collisionsResolved);
        System.out.println("anons_excluded=" + excludedAnons.size());
        System.out.println("members_srg_mapped=" + membersSrgMapped);
        System.out.println("members_descfallback_mapped=" + membersDescfallbackMapped);
        System.out.println("members_churned_type_unmapped=" + membersChurnedTypeUnmapped);
        System.out.println("members_left_unmapped=" + membersLeftUnmapped);
        System.out.println("members_hierarchy_propagated=" + membersHierarchyPropagated);
        System.out.println("hierarchy_divergent_slots=" + hierarchyDivergentSlots);
        System.out.println("members_hierarchy_unmapped=" + membersHierarchyUnmapped);
        System.out.println("fallback_packaged_by_ancestor=" + fallbackPackagedByAncestor);
        System.out.println("fallback_packaged_by_coupling=" + fallbackPackagedByCoupling);
        System.out.println("fallback_packaged_by_srg=" + fallbackPackagedBySrg);
    }

    // ============================================================================================
    // Source provisioning (download + cache, sha-verified where pinned).
    // ============================================================================================
    static final class Sources {
        Path joined1132;
        Path joined1144;
        Path clientMappings1144;
        Path serverMappings1144;
        Path client1132;
        Path server1132;
        Path client1144;
        Path intermediaryTiny;
    }

    // Pinned sha1 for the 1.13.2 server jar (from the 1.13.2 version manifest entry).
    private static final String SERVER_1132_SHA1 = "3737db93722a9e39eeada7c27e7aca28b144ffa7";

    private Sources ensureSources(Path cache) throws Exception {
        Sources s = new Sources();
        s.joined1132 = cache.resolve("joined_1132.tsrg");
        s.joined1144 = cache.resolve("joined_1144.tsrg");
        s.clientMappings1144 = cache.resolve("client_mappings_1144.txt");
        s.serverMappings1144 = cache.resolve("server_mappings_1144.txt");
        s.client1132 = cache.resolve("client_1132.jar");
        s.server1132 = cache.resolve("server_1132.jar");
        s.client1144 = cache.resolve("client_1144.jar");
        s.intermediaryTiny = cache.resolve("intermediary_1132.tiny");

        // If a prepopulated cache already has everything, skip all network.
        if (allExist(s)) {
            return s;
        }

        if (Files.notExists(s.joined1132)) {
            extractFromZip(mcpConfigUrl(MCP_1132), "config/joined.tsrg", s.joined1132, cache.resolve("mcp_1132.zip"));
        }
        if (Files.notExists(s.joined1144)) {
            extractFromZip(mcpConfigUrl(MCP_1144), "config/joined.tsrg", s.joined1144, cache.resolve("mcp_1144.zip"));
        }

        String v1144Json = null;
        String v1132Json = null;
        if (Files.notExists(s.clientMappings1144) || Files.notExists(s.serverMappings1144)
                || Files.notExists(s.client1144) || Files.notExists(s.client1132)) {
            String manifest = httpGetString(MANIFEST_URL);
            v1144Json = httpGetString(manifestVersionUrl(manifest, "1.14.4"));
            v1132Json = httpGetString(manifestVersionUrl(manifest, "1.13.2"));
        }
        if (Files.notExists(s.clientMappings1144)) {
            download(jsonDownloadUrl(v1144Json, "client_mappings"), s.clientMappings1144, null);
        }
        if (Files.notExists(s.serverMappings1144)) {
            download(jsonDownloadUrl(v1144Json, "server_mappings"), s.serverMappings1144, null);
        }
        if (Files.notExists(s.client1144)) {
            download(jsonDownloadUrl(v1144Json, "client"), s.client1144, null);
        }
        if (Files.notExists(s.client1132)) {
            download(jsonDownloadUrl(v1132Json, "client"), s.client1132, CLIENT_1132_SHA1);
        }
        if (Files.notExists(s.server1132)) {
            download(jsonDownloadUrl(v1132Json, "server"), s.server1132, SERVER_1132_SHA1);
        }
        if (Files.notExists(s.intermediaryTiny)) {
            extractFromZip(INTERMEDIARY_URL, "mappings/mappings.tiny", s.intermediaryTiny,
                    cache.resolve("intermediary_1132.jar"));
        }
        return s;
    }

    private static boolean allExist(Sources s) {
        return Files.exists(s.joined1132) && Files.exists(s.joined1144) && Files.exists(s.clientMappings1144)
                && Files.exists(s.serverMappings1144) && Files.exists(s.client1132) && Files.exists(s.server1132)
                && Files.exists(s.client1144) && Files.exists(s.intermediaryTiny);
    }

    private static String mcpConfigUrl(String version) {
        return FORGE_MAVEN + "de/oceanlabs/mcp/mcp_config/" + version + "/mcp_config-" + version + ".zip";
    }

    private static String manifestVersionUrl(String manifest, String id) {
        String key = "\"id\": \"" + id + "\"";
        String keyAlt = "\"id\":\"" + id + "\"";
        int idx = manifest.indexOf(key);
        if (idx < 0) {
            idx = manifest.indexOf(keyAlt);
        }
        if (idx < 0) {
            throw new IllegalStateException("version " + id + " not in manifest");
        }
        int urlIdx = manifest.indexOf("\"url\"", idx);
        int q1 = manifest.indexOf('"', manifest.indexOf(':', urlIdx) + 1);
        int q2 = manifest.indexOf('"', q1 + 1);
        return manifest.substring(q1 + 1, q2);
    }

    private static String jsonDownloadUrl(String versionJson, String key) {
        String anchor = "\"" + key + "\"";
        int idx = versionJson.indexOf(anchor);
        if (idx < 0) {
            throw new IllegalStateException("download " + key + " not in version json");
        }
        int urlIdx = versionJson.indexOf("\"url\"", idx);
        int q1 = versionJson.indexOf('"', versionJson.indexOf(':', urlIdx) + 1);
        int q2 = versionJson.indexOf('"', q1 + 1);
        return versionJson.substring(q1 + 1, q2);
    }

    private void extractFromZip(String url, String entry, Path out, Path zipCache) throws Exception {
        if (Files.notExists(zipCache)) {
            download(url, zipCache, null);
        }
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipCache))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().equals(entry)) {
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        }
        throw new IllegalStateException("entry " + entry + " not found in " + url);
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS).build();

    private static String httpGetString(String url) throws Exception {
        HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("GET " + url + " -> " + resp.statusCode());
        }
        return resp.body();
    }

    private static void download(String url, Path out, String expectSha1) throws Exception {
        log("[download] %s", url);
        HttpResponse<byte[]> resp = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("GET " + url + " -> " + resp.statusCode());
        }
        byte[] data = resp.body();
        if (expectSha1 != null) {
            String got = sha1(data);
            if (!got.equals(expectSha1)) {
                throw new IllegalStateException("sha1 mismatch for " + url + ": got " + got + " want " + expectSha1);
            }
        }
        Files.write(out, data);
    }

    private static String sha1(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static final boolean DEBUG = System.getenv("BRIDGE_DEBUG") != null;

    private static void log(String fmt, Object... args) {
        System.out.println(String.format(fmt, args));
    }
}
