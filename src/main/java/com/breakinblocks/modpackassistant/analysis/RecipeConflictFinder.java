package com.breakinblocks.modpackassistant.analysis;

import com.breakinblocks.modpackassistant.ModpackAssistant;
import com.breakinblocks.modpackassistant.report.CsvWriter;
import com.breakinblocks.modpackassistant.report.ReportWriter;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
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
        final List<Prepared> recipes = new ArrayList<>();
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
        final ItemStack result;

        Prepared(RecipeHolder<?> holder, int[][] slots, ItemStack result) {
            this.holder = holder;
            this.width = holder.value() instanceof ShapedRecipe shaped ? shaped.getWidth() : -1;
            this.slots = slots;
            this.occupied = IntStream.range(0, slots.length).filter(i -> slots[i] != null).toArray();
            this.result = result.copy();
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

        void add(Prepared prepared) {
            ItemStack result = prepared.result;
            if (!results.isEmpty()) {
                ItemStack first = results.getFirst();
                conflict |= !ItemStack.isSameItemSameComponents(first, result) || first.getCount() != result.getCount();
            }
            recipes.add(prepared.holder);
            results.add(result);
        }
    }

    private static final class Work {
        final int[] parent;
        final int[] seen;
        final Map<Signature, Integer> signatures = new HashMap<>();
        final IntArrayList representatives = new IntArrayList();
        final Int2ObjectOpenHashMap<IntArrayList> index = new Int2ObjectOpenHashMap<>();
        final Map<Integer, Members> members = new LinkedHashMap<>();
        int indexed;
        int representative;
        int[] anchor;
        int anchorItem;
        IntArrayList candidates;
        int candidate;
        int grouped;
        Iterator<Members> output;

        Work(int size) {
            parent = new int[size];
            seen = new int[size];
        }
    }

    private final ContextMap displayContext;
    private final List<Bucket> buckets = new ArrayList<>();
    private final Map<String, Bucket> byKey = new HashMap<>();
    private final List<Identifier> skipped = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    private final Map<Ingredient, int[]> itemIds = new IdentityHashMap<>();
    private int recipeCount;

    public RecipeConflictFinder(Level level) {
        this.displayContext = SlotDisplayContext.fromLevel(level);
    }

    public List<Bucket> buckets() {
        return buckets;
    }

    public int recipeCount() {
        return recipeCount;
    }

    public List<Identifier> skipped() {
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
        if (recipe.isSpecial()) {
            skipped.add(holder.id().identifier());
            return;
        }
        try {
            List<Ingredient> inputs = ingredients(recipe);
            ItemStack result = resultOf(holder, displayContext);
            if (inputs.isEmpty() || inputs.size() > 81 || result.isEmpty()
                    || inputs.stream().anyMatch(ingredient -> ingredient != null && (!ingredient.isSimple() || ingredient.isEmpty()))) {
                skipped.add(holder.id().identifier());
                return;
            }
            int[][] slots = new int[inputs.size()][];
            for (int i = 0; i < slots.length; i++) slots[i] = itemIds(inputs.get(i));
            Prepared prepared = new Prepared(holder, slots, result);
            String key = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()) + "|" + prepared.occupied.length;
            Bucket bucket = byKey.computeIfAbsent(key, ignored -> new Bucket(recipe.getType()));
            bucket.recipes.add(prepared);
            if (bucket.size() == 2) buckets.add(bucket);
        } catch (RuntimeException error) {
            skipped.add(holder.id().identifier());
            ModpackAssistant.LOGGER.debug("Skipped recipe {} without usable static inputs or output", holder.id().identifier(), error);
        }
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
            if (work.indexed < size) {
                indexNext(bucket, work);
            } else if (work.representative < work.representatives.size()) {
                compareNext(bucket, work);
            } else if (work.grouped < size) {
                int index = work.grouped++;
                work.members.computeIfAbsent(find(work.parent, index), ignored -> new Members())
                        .add(bucket.recipes.get(index));
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

    private static void indexNext(Bucket bucket, Work work) {
        int index = work.indexed++;
        work.parent[index] = index;
        Prepared prepared = bucket.recipes.get(index);
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
            for (int item : prepared.slots[slot]) {
                IntArrayList list = work.index.computeIfAbsent(item, ignored -> new IntArrayList());
                if (list.isEmpty() || list.getInt(list.size() - 1) != index) list.add(index);
            }
        }
    }

    private static void compareNext(Bucket bucket, Work work) {
        int a = work.representatives.getInt(work.representative);
        if (work.anchor == null) {
            work.anchor = anchor(work, bucket.recipes.get(a));
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
        if (rootA != rootB && sameInputs(bucket.recipes.get(a), bucket.recipes.get(b))) {
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
    private int[] itemIds(@Nullable Ingredient ingredient) {
        if (ingredient == null) return null;
        return itemIds.computeIfAbsent(ingredient, ignored -> ingredient.items()
                .mapToInt(item -> BuiltInRegistries.ITEM.getId(item.value()))
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

    private static List<Ingredient> ingredients(Recipe<?> recipe) {
        var placement = recipe.placementInfo();
        if (!(recipe instanceof ShapedRecipe)) return placement.ingredients();
        List<Ingredient> grid = new ArrayList<>();
        for (int index : placement.slotsToIngredientIndex()) {
            grid.add(index < 0 ? null : placement.ingredients().get(index));
        }
        return grid;
    }

    private static ItemStack resultOf(RecipeHolder<?> holder, ContextMap displayContext) {
        ItemStack result = ItemStack.EMPTY;
        for (RecipeDisplay display : holder.value().display()) {
            for (ItemStack candidate : display.result().resolveForStacks(displayContext)) {
                if (candidate.isEmpty()) continue;
                if (!result.isEmpty() && (!ItemStack.isSameItemSameComponents(result, candidate)
                        || result.getCount() != candidate.getCount())) return ItemStack.EMPTY;
                result = candidate;
            }
        }
        return result;
    }

    public String log(ReportWriter.Context context) {
        List<String> lines = new ArrayList<>(context.commentLines());
        appendSection(lines, "Conflicts", true);
        appendSection(lines, "Duplicates", false);
        lines.add("");
        lines.add("Skipped dynamic or unsupported recipes (" + skipped.size() + ")");
        lines.add("=".repeat(60));
        skipped.forEach(id -> lines.add(id.toString()));
        lines.add("");
        lines.add("Groups per mod");
        lines.add("=".repeat(60));
        Object2IntOpenHashMap<String> perMod = new Object2IntOpenHashMap<>();
        for (Group group : groups) {
            Set<String> mods = new TreeSet<>();
            group.recipes().forEach(holder -> mods.add(holder.id().identifier().getNamespace()));
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
                lines.add("    " + holder.id().identifier() + "  (" + holder.id().identifier().getNamespace() + ")  -> " + result.getCount() + "x " + BuiltInRegistries.ITEM.getKey(result.getItem()));
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
                        holder.id().identifier(), holder.id().identifier().getNamespace(), BuiltInRegistries.ITEM.getKey(result.getItem()), result.getCount());
            }
        }
        return csv.content();
    }
}
