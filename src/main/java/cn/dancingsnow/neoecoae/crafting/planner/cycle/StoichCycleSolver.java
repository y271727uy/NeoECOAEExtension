package cn.dancingsnow.neoecoae.crafting.planner.cycle;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import cn.dancingsnow.neoecoae.crafting.amount.PlannerAmount;
import cn.dancingsnow.neoecoae.crafting.planner.ECOCancellation;
import cn.dancingsnow.neoecoae.crafting.planner.compile.CompiledInput;
import cn.dancingsnow.neoecoae.crafting.planner.compile.CompiledPattern;
import cn.dancingsnow.neoecoae.crafting.planner.component.ComponentDependency;
import cn.dancingsnow.neoecoae.crafting.planner.result.ExecutionCountKnowledge;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import y271727uy.stoich.api.Stoich;
import y271727uy.stoich.api.StoichGraph;
import y271727uy.stoich.api.StoichResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Solves one cyclic component with the stoich kernel. The numbering and the rejection checks follow
 * {@link BoundedCycleSolver}, so the two answer the same component the same way; only the search runs in
 * the native library.
 */
public final class StoichCycleSolver implements CycleSolver {
    @Override
    public CycleSolveResult solve(CycleSolveRequest request, ECOCancellation cancellation) throws InterruptedException {
        cancellation.checkpoint();
        Map<IPatternDetails, CompiledPattern> unique = new LinkedHashMap<>();
        for (CompiledPattern pattern : request.component().patterns()) unique.putIfAbsent(pattern.details(), pattern);
        List<CompiledPattern> patterns = new ArrayList<>(unique.values());
        CycleSolveLimits limits = request.options().limits();
        if (limits.maxPatterns() > 0 && patterns.size() > limits.maxPatterns()) {
            return CycleSolveResult.failure(CycleSolveStatus.TOO_COMPLEX, CycleSolveDiagnostic.Code.PATTERN_LIMIT_EXCEEDED,
                    "Cyclic component has " + patterns.size() + " transitions, limit is " + limits.maxPatterns());
        }
        for (CompiledPattern pattern : patterns) {
            String reason = unsupported(pattern);
            if (reason != null) {
                CycleSolveStatus status = reason.startsWith("AMOUNT_UNREPRESENTABLE:")
                        ? CycleSolveStatus.UNREPRESENTABLE : CycleSolveStatus.UNSUPPORTED_PATTERN;
                CycleSolveDiagnostic.Code code = status == CycleSolveStatus.UNREPRESENTABLE
                        ? CycleSolveDiagnostic.Code.EXECUTION_AMOUNT_UNREPRESENTABLE
                        : CycleSolveDiagnostic.Code.UNSUPPORTED_PATTERN;
                return CycleSolveResult.failure(status, code, "Pattern " + pattern.id() + " " + reason);
            }
        }

        Object2IntLinkedOpenHashMap<AEKey> index = new Object2IntLinkedOpenHashMap<>();
        index.defaultReturnValue(-1);
        for (AEKey member : request.component().members()) index.putIfAbsent(member, index.size());
        for (CompiledPattern pattern : patterns) {
            for (CompiledInput input : pattern.inputs()) index.putIfAbsent(input.key(), index.size());
            for (GenericStack output : pattern.grossOutputs()) index.putIfAbsent(output.what(), index.size());
        }
        for (AEKey required : request.plannerRequiredOutputs().keySet()) index.putIfAbsent(required, index.size());
        if (limits.maxKeys() > 0 && index.size() > limits.maxKeys()) {
            return CycleSolveResult.failure(CycleSolveStatus.TOO_COMPLEX, CycleSolveDiagnostic.Code.KEY_LIMIT_EXCEEDED,
                    "Cyclic component touches " + index.size() + " keys, limit is " + limits.maxKeys());
        }

        int items = index.size();
        int recipes = patterns.size();
        List<AEKey> keys = new ArrayList<>(index.keySet());
        boolean[] importable = importable(request, index, items);
        long[] stock = new long[items];
        long[] target = new long[items];
        for (int item = 0; item < items; item++) {
            stock[item] = request.stockOf(keys.get(item));
            PlannerAmount required = request.requiredOutputAmount(keys.get(item));
            if (!required.fitsLong()) {
                return unrepresentable("required output for " + keys.get(item));
            }
            target[item] = required.longValueExact();
        }

        StoichGraph.Builder builder = StoichGraph.builder(recipes, items);
        for (int recipe = 0; recipe < recipes; recipe++) {
            CompiledPattern pattern = patterns.get(recipe);
            long[] consumed = new long[items];
            long[] produced = new long[items];
            for (CompiledInput input : pattern.inputs()) consumed[index.getInt(input.key())] += input.amountPerPattern().longValueExact();
            for (GenericStack output : pattern.grossOutputs()) produced[index.getInt(output.what())] += output.amount();
            int[] touched = touched(consumed, produced);
            long[] consumedSparse = new long[touched.length];
            long[] producedSparse = new long[touched.length];
            for (int entry = 0; entry < touched.length; entry++) {
                consumedSparse[entry] = consumed[touched[entry]];
                producedSparse[entry] = produced[touched[entry]];
            }
            builder.column(recipe, touched, consumedSparse, producedSparse);
        }
        for (int item = 0; item < items; item++) {
            builder.stock(item, stock[item]);
            builder.target(item, target[item]);
            if (importable[item]) builder.importable(item);
        }
        if (limits.maxStates() > 0) builder.maxStates(limits.maxStates());

        StoichResult result = Stoich.solve(builder.build());
        return translate(result, patterns, keys, recipes, items);
    }

    private static CycleSolveResult translate(StoichResult result, List<CompiledPattern> patterns, List<AEKey> keys,
            int recipes, int items) {
        CycleSolveMetrics metrics = new CycleSolveMetrics(items, recipes, result.states(), result.states(),
                result.steps().length, 0, result.status() == StoichResult.Status.BUDGET_EXHAUSTED, false);
        if (result.status() == StoichResult.Status.BUDGET_EXHAUSTED) {
            return CycleSolveResult.failure(CycleSolveStatus.UNKNOWN_BUDGET,
                    CycleSolveDiagnostic.Code.STATE_BUDGET_EXHAUSTED, "stoich closed the search on its state budget");
        }
        if (result.status() == StoichResult.Status.UNREPRESENTABLE) {
            return unrepresentable("stoich result");
        }
        if (result.status() == StoichResult.Status.REJECTED) {
            return CycleSolveResult.failure(CycleSolveStatus.UNKNOWN_BUDGET,
                    CycleSolveDiagnostic.Code.STATE_BUDGET_EXHAUSTED, "stoich rejected the request: " + result.detail());
        }

        Map<IPatternDetails, PlannerAmount> exactTimes = new LinkedHashMap<>();
        Map<IPatternDetails, Long> times = new LinkedHashMap<>();
        for (int recipe = 0; recipe < recipes; recipe++) {
            if (result.firings()[recipe] > 0) {
                exactTimes.put(patterns.get(recipe).details(), PlannerAmount.of(result.firings()[recipe]));
                times.put(patterns.get(recipe).details(), result.firings()[recipe]);
            }
        }
        List<PatternRun> plan = new ArrayList<>();
        for (StoichResult.Step step : result.steps()) {
            int width = step.repeatWidth() == 0 ? 1 : step.repeatWidth();
            plan.add(new PatternRun(patterns.get(step.recipe()), step.count(), width, step.repetitions()));
        }
        List<CycleSolveDiagnostic> diagnostics = new ArrayList<>();
        boolean shortfall = false;
        for (long amount : result.shortfall()) shortfall |= amount > 0;
        diagnostics.add(new CycleSolveDiagnostic(shortfall
                ? CycleSolveDiagnostic.Code.SEED_SHORTFALL : CycleSolveDiagnostic.Code.SEED_COVERED_BY_STOCK,
                shortfall ? "stoich reports a seed the stock does not cover" : "stoich seed is covered by stock"));

        CycleSolveStatus status = switch (result.status()) {
            case SUCCESS -> CycleSolveStatus.SUCCESS;
            case INSUFFICIENT_SEED, INFEASIBLE -> CycleSolveStatus.INSUFFICIENT_EXTERNAL_INPUT;
            default -> CycleSolveStatus.UNKNOWN_BUDGET;
        };
        return new CycleSolveResult(status, ExecutionCountKnowledge.EXACT, exactTimes, times,
                positive(result.external(), keys), positive(result.seed(), keys), positive(result.shortfall(), keys),
                Map.of(), Map.of(), List.of(), plan, diagnostics, metrics, startup(result.startup(), keys));
    }

    private static boolean[] importable(CycleSolveRequest request, Object2IntLinkedOpenHashMap<AEKey> index, int items) {
        boolean[] importable = new boolean[items];
        LinkedHashSet<AEKey> members = new LinkedHashSet<>(request.component().members());
        for (ComponentDependency dependency : request.externalResourceBoundary()) {
            for (var relationship : dependency.relationships()) {
                AEKey key = relationship.requiredInput();
                int slot = index.getInt(key);
                if (slot >= 0 && !members.contains(key)) importable[slot] = true;
            }
        }
        return importable;
    }

    private static int[] touched(long[] consumed, long[] produced) {
        int count = 0;
        for (int item = 0; item < consumed.length; item++) if (consumed[item] != 0 || produced[item] != 0) count++;
        int[] touched = new int[count];
        int cursor = 0;
        for (int item = 0; item < consumed.length; item++) {
            if (consumed[item] != 0 || produced[item] != 0) touched[cursor++] = item;
        }
        return touched;
    }

    private static Map<AEKey, Long> positive(long[] amounts, List<AEKey> keys) {
        Map<AEKey, Long> result = new LinkedHashMap<>();
        for (int item = 0; item < amounts.length; item++) if (amounts[item] > 0) result.put(keys.get(item), amounts[item]);
        return result;
    }

    private static List<Map<AEKey, Long>> startup(long[][] startup, List<AEKey> keys) {
        List<Map<AEKey, Long>> result = new ArrayList<>();
        for (long[] candidate : startup) {
            Map<AEKey, Long> missing = positive(candidate, keys);
            if (!missing.isEmpty()) result.add(missing);
        }
        return result;
    }

    private static String unsupported(CompiledPattern pattern) {
        if (!pattern.fastSupported()) {
            return pattern.unsupportedReason() == null || pattern.unsupportedReason().isEmpty()
                    ? "NOT_FAST_SUPPORTED" : pattern.unsupportedReason();
        }
        if (pattern.outputPerPattern().signum() <= 0) return "PRIMARY_OUTPUT_MISMATCH";
        for (CompiledInput input : pattern.inputs()) {
            if (!input.fastSupported()) {
                return input.unsupportedReason() == null || input.unsupportedReason().isEmpty()
                        ? "UNSUPPORTED_INPUT" : input.unsupportedReason();
            }
            if (input.amountPerPattern().signum() <= 0) return "INVALID_INPUT_AMOUNT";
            if (!input.amountPerPattern().fitsLong()) {
                return "AMOUNT_UNREPRESENTABLE: input=" + input.key() + " amount=" + input.amountPerPattern();
            }
        }
        if (pattern.outputs().isEmpty()) return "NO_OUTPUTS";
        for (GenericStack output : pattern.outputs()) {
            if (output == null || output.what() == null || output.amount() <= 0) return "INVALID_OUTPUT";
        }
        return null;
    }

    private static CycleSolveResult unrepresentable(String what) {
        return CycleSolveResult.failure(CycleSolveStatus.UNREPRESENTABLE,
                CycleSolveDiagnostic.Code.EXECUTION_AMOUNT_UNREPRESENTABLE, what + " exceeds the long range");
    }
}
