package com.breakinblocks.modpackassistant.analysis;

import com.breakinblocks.modpackassistant.report.CsvWriter;
import com.breakinblocks.modpackassistant.report.ReportWriter;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.stream.IntStream;

public final class RecipeConflictFinder {
    public record Group(int id, RecipeType<?> type, List<RecipeHolder<?>> recipes, List<ItemStack> results, boolean conflict) {
    }

    public static final class Bucket {
        final RecipeType<?> type;
        final List<RecipeHolder<?>> recipes = new ArrayList<>();
        private Work work;

        Bucket(RecipeType<?> type) {
            this.type = type;
        }

        public int size() {
            return recipes.size();
        }
    }

    private static final class Prepared {
        final RecipeHolder<?> holder;
        final int width;
        final int[][] slots;
        final int[] occupied;

        Prepared(RecipeHolder<?> holder, int[][] slots) {
            this.holder = holder;
            this.width = holder.value() instanceof ShapedRecipe shaped ? shaped.getWidth() : -1;
            this.slots = slots;
            this.occupied = IntStream.range(0, slots.length).filter(i -> slots[i] != null).toArray();
        }

        @Nullable
        Signature signature() {
            List<IntArrayList> lists = new ArrayList<>();
            if (width >= 0) {
                for (int[] slot : slots) {
                    if (slot != null && slot.length == 0) return null;
                    lists.add(slot == null ? null : IntArrayList.wrap(slot));
                }
            } else {
                int[][] sorted = new int[occupied.length][];
                for (int i = 0; i < occupied.length; i++) {
                    sorted[i] = slots[occupied[i]];
                    if (sorted[i].length == 0) return null;
                }
                Arrays.sort(sorted, Arrays::compare);
                for (int[] slot : sorted) lists.add(IntArrayList.wrap(slot));
            }
            return new Signature(width, lists);
        }
    }

    private record Signature(int width, List<IntArrayList> slots) {
    }

    private static final class Members {
        final List<RecipeHolder<?>> recipes = new ArrayList<>();
        final List<ItemStack> results = new ArrayList<>();
        boolean conflict;

        void add(Prepared prepared, HolderLookup.Provider lookup) {
            ItemStack result = prepared.holder.value().getResultItem(lookup);
            if (!results.isEmpty()) {
                ItemStack first = results.getFirst();
                conflict |= !ItemStack.isSameItemSameComponents(first, result) || first.getCount() != result.getCount();
            }
            recipes.add(prepared.holder);
            results.add(result);
        }
    }

    private static final class Work {
        final Prepared[] prepared;
        final int[] parent;
        final int[] seen;
        final Map<Signature, Integer> signatures = new HashMap<>();
        final IntArrayList representatives = new IntArrayList();
        final Int2ObjectOpenHashMap<IntArrayList> index = new Int2ObjectOpenHashMap<>();
        final Map<Integer, Members> members = new LinkedHashMap<>();
        int preparedCount;
        int representative;
        int[] anchor;
        int anchorItem;
        IntArrayList candidates;
        int candidate;
        int grouped;
        Iterator<Members> output;

        Work(int size) {
            prepared = new Prepared[size];
            parent = new int[size];
            seen = new int[size];
        }
    }

    private final HolderLookup.Provider lookup;
    private final List<Bucket> buckets = new ArrayList<>();
    private final Map<String, Bucket> byKey = new HashMap<>();
    private final List<ResourceLocation> skipped = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    private final Map<Ingredient, int[]> itemIds = new IdentityHashMap<>();
    private int recipeCount;

    public RecipeConflictFinder(HolderLookup.Provider lookup) {
        this.lookup = lookup;
    }

    public List<Bucket> buckets() {
        return buckets;
    }

    public int recipeCount() {
        return recipeCount;
    }

    public List<ResourceLocation> skipped() {
        return skipped;
    }

    public long conflictCount() {
        return groups.stream().filter(Group::conflict).count();
    }

    public long duplicateCount() {
        return groups.stream().filter(group -> !group.conflict()).count();
    }

    public void prepare(Collection<RecipeHolder<?>> recipes, @Nullable RecipeType<?> filter) {
        recipes.forEach(holder -> addRecipe(holder, filter));
    }

    public void addRecipe(RecipeHolder<?> holder, @Nullable RecipeType<?> filter) {
        Recipe<?> recipe = holder.value();
        if (filter != null && recipe.getType() != filter) return;
        recipeCount++;
        if (recipe.isSpecial() || recipe.getIngredients().isEmpty()
                || recipe.getIngredients().size() > 81
                || recipe.getIngredients().stream().anyMatch(ingredient -> !ingredient.isSimple())) {
            skipped.add(holder.id());
            return;
        }
        long count = recipe.getIngredients().stream().filter(ingredient -> !ingredient.isEmpty()).count();
        String key = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()) + "|" + count;
        Bucket bucket = byKey.computeIfAbsent(key, ignored -> new Bucket(recipe.getType()));
        bucket.recipes.add(holder);
        if (bucket.size() == 2) buckets.add(bucket);
    }

    public void process(Bucket bucket) {
        while (!processBatch(bucket, 256)) {}
    }

    public boolean processBatch(Bucket bucket, int budget) {
        if (budget < 1) throw new IllegalArgumentException("budget must be positive");
        if (bucket.work == null) bucket.work = new Work(bucket.size());
        Work work = bucket.work;
        int size = bucket.size();
        for (int operation = 0; operation < budget; operation++) {
            if (work.preparedCount < size) {
                prepareNext(bucket, work);
            } else if (work.representative < work.representatives.size()) {
                compareNext(work);
            } else if (work.grouped < size) {
                int index = work.grouped++;
                work.members.computeIfAbsent(find(work.parent, index), ignored -> new Members())
                        .add(work.prepared[index], lookup);
            } else {
                if (work.output == null) work.output = work.members.values().iterator();
                if (!work.output.hasNext()) return true;
                Members members = work.output.next();
                if (members.recipes.size() > 1) {
                    groups.add(new Group(groups.size() + 1, bucket.type, members.recipes, members.results, members.conflict));
                }
            }
        }
        return work.output != null && !work.output.hasNext();
    }

    private void prepareNext(Bucket bucket, Work work) {
        int index = work.preparedCount++;
        work.parent[index] = index;
        RecipeHolder<?> holder = bucket.recipes.get(index);
        List<Ingredient> ingredients = holder.value().getIngredients();
        int[][] slots = new int[ingredients.size()][];
        for (int i = 0; i < slots.length; i++) slots[i] = itemIds(ingredients.get(i));
        Prepared prepared = new Prepared(holder, slots);
        work.prepared[index] = prepared;
        Signature signature = prepared.signature();
        if (signature != null) {
            Integer existing = work.signatures.putIfAbsent(signature, index);
            if (existing != null) {
                work.parent[index] = existing;
                return;
            }
        }
        work.representatives.add(index);
        for (int slot : prepared.occupied) {
            for (int item : slots[slot]) {
                IntArrayList list = work.index.computeIfAbsent(item, ignored -> new IntArrayList());
                if (list.isEmpty() || list.getInt(list.size() - 1) != index) list.add(index);
            }
        }
    }

    private void compareNext(Work work) {
        int a = work.representatives.getInt(work.representative);
        if (work.anchor == null) {
            work.anchor = anchor(work, work.prepared[a]);
            work.anchorItem = 0;
            work.candidates = null;
            return;
        }
        if (work.candidates == null) {
            if (work.anchorItem >= work.anchor.length) {
                work.representative++;
                work.anchor = null;
                return;
            }
            IntArrayList list = work.index.get(work.anchor[work.anchorItem++]);
            if (list != null) {
                work.candidates = list;
                work.candidate = firstAfter(list, a);
            }
            return;
        }
        if (work.candidate >= work.candidates.size()) {
            work.candidates = null;
            return;
        }
        int b = work.candidates.getInt(work.candidate++);
        if (work.seen[b] == a + 1) return;
        work.seen[b] = a + 1;
        int rootA = find(work.parent, a);
        int rootB = find(work.parent, b);
        if (rootA != rootB && sameInputs(work.prepared[a], work.prepared[b])) {
            work.parent[rootA] = rootB;
        }
    }

    private static int[] anchor(Work work, Prepared prepared) {
        int[] best = new int[0];
        long bestCost = Long.MAX_VALUE;
        for (int slot : prepared.occupied) {
            long cost = 0;
            for (int item : prepared.slots[slot]) {
                IntArrayList list = work.index.get(item);
                if (list != null) cost += list.size();
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = prepared.slots[slot];
            }
        }
        return best;
    }

    private static int firstAfter(IntArrayList list, int value) {
        int low = 0;
        int high = list.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (list.getInt(mid) <= value) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    @Nullable
    private int[] itemIds(Ingredient ingredient) {
        if (ingredient.isEmpty()) return null;
        return itemIds.computeIfAbsent(ingredient, ignored -> Arrays.stream(ingredient.getItems())
                .mapToInt(stack -> BuiltInRegistries.ITEM.getId(stack.getItem()))
                .distinct()
                .sorted()
                .toArray());
    }

    private static int find(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    private static boolean sameInputs(Prepared a, Prepared b) {
        if (a.occupied.length != b.occupied.length) return false;
        if (a.width >= 0 && b.width >= 0) {
            if (a.width != b.width || a.slots.length != b.slots.length) return false;
            return shapedOverlap(a, b, a.width, false) || shapedOverlap(a, b, a.width, true);
        }
        int[] matched = new int[b.occupied.length];
        Arrays.fill(matched, -1);
        for (int i = 0; i < a.occupied.length; i++) {
            if (!match(a, b, i, matched, new boolean[matched.length])) return false;
        }
        return true;
    }

    private static boolean shapedOverlap(Prepared a, Prepared b, int width, boolean mirrored) {
        for (int i = 0; i < a.slots.length; i++) {
            int j = mirrored ? i / width * width + width - 1 - i % width : i;
            if (!overlaps(a.slots[i], b.slots[j])) return false;
        }
        return true;
    }

    private static boolean match(Prepared a, Prepared b, int left, int[] matched, boolean[] seen) {
        for (int right = 0; right < matched.length; right++) {
            if (seen[right] || !overlaps(a.slots[a.occupied[left]], b.slots[b.occupied[right]])) continue;
            seen[right] = true;
            if (matched[right] < 0 || match(a, b, matched[right], matched, seen)) {
                matched[right] = left;
                return true;
            }
        }
        return false;
    }

    private static boolean overlaps(@Nullable int[] a, @Nullable int[] b) {
        if (a == null || b == null) return a == b;
        int i = 0;
        int j = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) return true;
            if (a[i] < b[j]) i++;
            else j++;
        }
        return false;
    }

    public String log(ReportWriter.Context context) {
        List<String> lines = new ArrayList<>(context.commentLines());
        appendSection(lines, "Conflicts", true);
        appendSection(lines, "Duplicates", false);
        lines.add("");
        lines.add("Skipped dynamic recipes (" + skipped.size() + ")");
        lines.add("=".repeat(60));
        skipped.forEach(id -> lines.add(id.toString()));
        lines.add("");
        lines.add("Groups per mod");
        lines.add("=".repeat(60));
        Object2IntOpenHashMap<String> perMod = new Object2IntOpenHashMap<>();
        for (Group group : groups) {
            Set<String> mods = new TreeSet<>();
            group.recipes().forEach(holder -> mods.add(holder.id().getNamespace()));
            mods.forEach(mod -> perMod.addTo(mod, 1));
        }
        perMod.object2IntEntrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getIntValue(), a.getIntValue()))
                .forEach(entry -> lines.add(entry.getKey() + ": " + entry.getIntValue()));
        return String.join("\n", lines) + "\n";
    }

    private void appendSection(List<String> lines, String title, boolean conflict) {
        List<Group> selected = groups.stream().filter(group -> group.conflict() == conflict).toList();
        lines.add("");
        lines.add(title + " (" + selected.size() + ")");
        lines.add("=".repeat(60));
        for (Group group : selected) {
            lines.add("Group " + group.id() + " [" + BuiltInRegistries.RECIPE_TYPE.getKey(group.type()) + "]");
            for (int i = 0; i < group.recipes().size(); i++) {
                RecipeHolder<?> holder = group.recipes().get(i);
                ItemStack result = group.results().get(i);
                lines.add("    " + holder.id() + "  (" + holder.id().getNamespace() + ")  -> " + result.getCount() + "x " + BuiltInRegistries.ITEM.getKey(result.getItem()));
            }
        }
    }

    public String csv(ReportWriter.Context context) {
        CsvWriter csv = new CsvWriter().comments(context.headerLines());
        csv.row("group", "kind", "recipe_type", "recipe", "mod", "result", "result_count");
        for (Group group : groups) {
            for (int i = 0; i < group.recipes().size(); i++) {
                RecipeHolder<?> holder = group.recipes().get(i);
                ItemStack result = group.results().get(i);
                csv.row(group.id(), group.conflict() ? "conflict" : "duplicate", BuiltInRegistries.RECIPE_TYPE.getKey(group.type()),
                        holder.id(), holder.id().getNamespace(), BuiltInRegistries.ITEM.getKey(result.getItem()), result.getCount());
            }
        }
        return csv.content();
    }
}
