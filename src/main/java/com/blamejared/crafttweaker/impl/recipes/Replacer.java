package com.blamejared.crafttweaker.impl.recipes;

import com.blamejared.crafttweaker.CraftTweaker;
import com.blamejared.crafttweaker.api.CraftTweakerAPI;
import com.blamejared.crafttweaker.api.annotations.ZenRegister;
import com.blamejared.crafttweaker.api.item.IIngredient;
import com.blamejared.crafttweaker.api.item.IItemStack;
import com.blamejared.crafttweaker.api.managers.IRecipeManager;
import com.blamejared.crafttweaker.api.recipes.GatherReplacementExclusionEvent;
import com.blamejared.crafttweaker.api.recipes.IReplacementRule;
import com.blamejared.crafttweaker.api.zencode.impl.util.PositionUtil;
import com.blamejared.crafttweaker.impl.brackets.RecipeTypeBracketHandler;
import com.blamejared.crafttweaker.impl.recipes.replacement.FullIngredientReplacementRule;
import com.blamejared.crafttweaker.impl.recipes.replacement.IngredientReplacementRule;
import com.blamejared.crafttweaker.impl.recipes.replacement.ReplacerAction;
import com.blamejared.crafttweaker.impl.recipes.replacement.StackTargetingReplacementRule;
import com.blamejared.crafttweaker.impl.recipes.wrappers.WrapperRecipe;
import com.blamejared.crafttweaker.impl.util.NameUtils;
import com.blamejared.crafttweaker_annotations.annotations.Document;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.Lazy;
import org.openzen.zencode.java.ZenCodeType;
import org.openzen.zencode.shared.CodePosition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Handles the replacing of ingredients in recipes for various {@link IRecipeManager}s and {@link WrapperRecipe}s.
 *
 * <p>Differently from various other mechanisms in CraftTweaker, each replacement that gets specified from within a
 * {@code Replacer} doesn't get run immediately, rather it gets stored and all replacements are then applied together
 * when the {@link #execute()} method is called. This change is done so that multiple replacements can be performed at
 * the same time, with a net gain on performance.</p>
 *
 * <p><strong>Note</strong> that replacement must be explicitly supported by modded recipe types and managers, meaning
 * that a {@code Replacer} may not be able to perform replacement on a certain set of recipes. If this is the case, a
 * warning will be printed in the logs, so that you may review it.</p>
 *
 * <p>Creating a {@code Replacer} gets done via the various {@code forXxx} methods, where {@code Xxx} identifies any
 * suffix. Refer to the specific documentation to get more information on their behavior.</p>
 *
 * <p>The various {@code replace} methods, listed both in this page and in other mod's possible expansions, then allow
 * you to specify what should be the replacements that need to be carried out by the {@code Replacer} itself.</p>
 *
 * <p>All recipes that get replaced by a {@code Replacer} get renamed according to a set naming scheme. You can modify
 * completely by providing a lambda via {@link #useForRenaming(BiFunction)}, or just for a specific set of recipes via
 * {@link #explicitlyRename(ResourceLocation, String)}.</p>
 *
 * <p>An example usage of a {@code Replacer} could be
 * {@code Replacer.forTypes(crafingTable).replace(<item:minecraft:string>, <item:minecraft:diamond>).execute();}</p>
 *
 * @docParam this Replacer.forAllTypes()
 */
@ZenRegister
@ZenCodeType.Name("crafttweaker.api.recipe.Replacer")
@Document("vanilla/api/recipe/Replacer")
public final class Replacer {
    private static final Function<ResourceLocation, ResourceLocation> DEFAULT_REPLACER =
            id -> NameUtils.isAutogeneratedName(id)? id : NameUtils.generateNameFrom(id.getNamespace() + "." + id.getPath());
    private static final Supplier<BiFunction<ResourceLocation, String, String>> DEFAULT_CUSTOM_FUNCTION = Lazy.concurrentOf(
            () -> (id, original) -> original
    );
    private static final Map<IRecipeManager, Collection<ResourceLocation>> DEFAULT_EXCLUSIONS = new HashMap<>();
    
    private final Collection<IRecipeManager> targetedManagers;
    private final Collection<? extends IRecipe<?>> targetedRecipes;
    private final List<IReplacementRule> rules;
    private final Map<ResourceLocation, String> userRenames;
    private final Set<ResourceLocation> userExclusionList;
    
    private BiFunction<ResourceLocation, String, String> userRenamingFunction;
    
    private Replacer(final Collection<? extends IRecipe<?>> recipes, final Collection<IRecipeManager> managers) {
        this.targetedManagers = Collections.unmodifiableCollection(managers);
        this.targetedRecipes = Collections.unmodifiableCollection(recipes);
        this.rules = new ArrayList<>();
        this.userRenames = new TreeMap<>();
        this.userExclusionList = new HashSet<>();
        this.userRenamingFunction = null;
    }
    
    /**
     * Creates a {@code Replacer} that targets only the specified {@link WrapperRecipe}s.
     *
     * <p>In other words, the replacer will perform ingredient replacement <strong>only</strong> on the recipes that
     * are given in this list.</p>
     *
     * @param recipes The recipes that should be targeted by the replacer. It must be at least one.
     * @return A new {@code Replacer} that targets only the specified recipes.
     *
     * @docParam recipes craftingTable.getRecipeByName("minecraft:emerald_block")
     */
    @ZenCodeType.Method
    public static Replacer forRecipes(final WrapperRecipe... recipes) {
        if (recipes.length <= 0) {
            throw new IllegalArgumentException("Unable to create a replacer without any targeted recipes");
        }
        return new Replacer(Arrays.stream(recipes).map(WrapperRecipe::getRecipe).collect(Collectors.toSet()), Collections.emptyList());
    }
    
    /**
     * Creates a {@code Replacer} that targets only the specified {@link IRecipeManager}s.
     *
     * <p>In other words, the replacer will perform ingredient replacement <strong>only</strong> on the managers that
     * are given in this list.</p>
     *
     * @param managers The managers that will be targeted by the replacer. It must be at least one.
     * @return A new {@code Replacer} that targets only the specified managers.
     *
     * @docParam managers smithing
     */
    @ZenCodeType.Method
    public static Replacer forTypes(final IRecipeManager... managers) {
        if (managers.length <= 0) {
            throw new IllegalArgumentException("Unable to create a replacer without any targeted recipe types");
        }
        return new Replacer(Collections.emptyList(), new HashSet<>(Arrays.asList(managers)));
    }
    
    /**
     * Creates a {@code Replacer} that will perform replacements globally.
     *
     * <p>In other words, the replacer will perform ingredient replacement on <strong>every</strong> recipe manager in
     * the game, as long as it supports replacement.</p>
     *
     * @return A new global {@code Replacer}.
     */
    @ZenCodeType.Method
    public static Replacer forAllTypes() {
        return forAllTypesExcluding();
    }
    
    /**
     * Creates a {@code Replacer} that will perform replacements on all {@link IRecipeManager}s except the ones
     * specified.
     *
     * @param managers The managers to exclude from the replacer.
     * @return A new {@code Replacer} that targets all managers except the ones specified.
     *
     * @docParam managers stoneCutter
     */
    @ZenCodeType.Method
    public static Replacer forAllTypesExcluding(final IRecipeManager... managers) {
        final List<IRecipeManager> managerList = Arrays.asList(managers);
        return new Replacer(
                Collections.emptyList(),
                RecipeTypeBracketHandler.getManagerInstances().stream().filter(manager -> !managerList.contains(manager)).collect(Collectors.toSet())
        );
    }
    
    /**
     * Excludes a set of recipes, identified by their name, from undergoing replacement.
     *
     * @param recipes The list of recipes that should be excluded.
     * @return This replacer for chaining.
     *
     * @docParam recipes <resource:minecraft:comparator>
     */
    @ZenCodeType.Method
    public Replacer excluding(final ResourceLocation... recipes) {
        this.userExclusionList.addAll(Arrays.asList(recipes));
        return this;
    }
    
    /**
     * Replaces every match of the {@code from} {@link IIngredient} with the one given in {@code to}.
     *
     * <p>This replacement behavior is recursive, meaning that any {@code IIngredient} that gets found is looped
     * recursively trying to identify matches. As an example, attempting to replace {@code <item:minecraft:stone>} with
     * {@code <item:minecraft:diamond>} will perform this replacement even in compound ingredients, such as {@code
     * <item:minecraft:stone> | <item:minecraft:gold_ingot>} or {@code <tag:items:minecraft:stones>} (supposing that
     * {@code minecraft:stones} is a tag that contains {@code minecraft:stone} among other items).</p>
     *
     * <p>If this behavior is not desired, refer to {@link #replaceFully(IIngredient, IIngredient)} instead.</p>
     *
     * <p>This method is a specialized by {@link #replace(IItemStack, IIngredient)} for {@link IItemStack}s and should
     * be preferred in these cases.</p>
     *
     * @param from An {@link IIngredient} that will be used to match stacks that need to be replaced.
     * @param to The replacement {@link IIngredient}.
     * @return This replacer for chaining.
     *
     * @docParam from <tag:items:forge:storage_blocks/redstone>
     * @docParam to <item:minecraft:diamond_block>
     */
    @ZenCodeType.Method
    public Replacer replace(final IIngredient from, final IIngredient to) {
        if (from instanceof IItemStack) return this.replace((IItemStack) from, to);
        return this.addReplacementRule(IngredientReplacementRule.create(from, to));
    }
    
    /**
     * Replaces every match of the {@code from} {@link IItemStack} with the one given in {@code to}.
     *
     * <p>This replacement behavior is recursive, meaning that any {@code IIngredient} that gets found is looped
     * recursively trying to identify matches. As an example, attempting to replace {@code <item:minecraft:stone>} with
     * {@code <item:minecraft:diamond>} will perform this replacement even in compound ingredients, such as {@code
     * <item:minecraft:stone> | <item:minecraft:gold_ingot>} or {@code <tag:items:minecraft:stones>} (supposing that
     * {@code minecraft:stones} is a tag that contains {@code minecraft:stone} among other items).</p>
     *
     * <p>If this behavior is not desired, refer to {@link #replaceFully(IIngredient, IIngredient)} instead.</p>
     *
     * <p>This method is a specialization of {@link #replace(IIngredient, IIngredient)} for {@link IItemStack}s and
     * should be preferred in these cases.</p>
     *
     * @param from An {@link IItemStack} that will be used to match stacks that need to be replaced.
     * @param to The replacement {@link IIngredient}.
     * @return This replacer for chaining.
     *
     * @docParam from <item:minecraft:coal_block>
     * @docParam to <item:minecraft:diamond_block>
     */
    @ZenCodeType.Method("replaceStack") // TODO("Move to 'replace' once ambiguous call issue has been fixed")
    public Replacer replace(final IItemStack from, final IIngredient to) {
        return this.addReplacementRule(StackTargetingReplacementRule.create(from, to));
    }
    
    /**
     * Replaces every instance of the target {@code from} {@link IIngredient} with the {@code to} one.
     *
     * <p>This replacement behavior is not recursive, meaning that the {@code IIngredient}s will be matched closely
     * instead of recursively. As an example, attempting to replace fully {@code <item:minecraft:stone>} will only
     * replace ingredients that explicitly specify {@code <item:minecraft:stone>} as an input, while compound
     * ingredients such as {@code <item:minecraft:stone> | <item:minecraft:gold_ingot>} won't be replaced.</p>
     *
     * <p>If this behavior is not desired, refer to {@link #replace(IIngredient, IIngredient)} instead.</p>
     *
     * @param from An {@link IIngredient} that will be used to match to specify the ingredient to replace.
     * @param to The replacement {@link IIngredient}.
     * @return This replacer for chaining.
     *
     * @docParam from <tag:items:minecraft:anvil>
     * @docParam to <tag:items:minecraft:flowers>
     */
    @ZenCodeType.Method
    public Replacer replaceFully(final IIngredient from, final IIngredient to) {
        return this.addReplacementRule(FullIngredientReplacementRule.create(from, to));
    }
    
    /**
     * Indicates that the recipe with the given {@code oldName} should be renamed to the {@code newName}.
     *
     * <p>This rename will only be applied if a replacement is carried out. Moreover, the given new name will also be
     * fixed according to {@link NameUtils#fixing(String)}.</p>
     *
     * @param oldName The {@link ResourceLocation} of the name of the recipe that should be renamed.
     * @param newName The new name of the recipe.
     * @return This replacer for chaining.
     *
     * @docParam oldName <resource:minecraft:birch_sign>
     * @docParam newName "damn_hard_birch_sign"
     */
    @ZenCodeType.Method
    public Replacer explicitlyRename(final ResourceLocation oldName, final String newName) {
        final String actualNewName = this.fix(newName, oldName);
        if (this.userRenames.containsKey(oldName) && !this.userRenames.get(oldName).equals(actualNewName)) {
            CraftTweakerAPI.logError(
                    "The same old name '%s' has been specified twice for renaming with two different strings '%s' and '%s': only the former will apply",
                    oldName,
                    this.userRenames.get(oldName),
                    newName
            );
            return this;
        }
        
        this.userRenames.put(oldName, actualNewName);
        return this;
    }
    
    /**
     * Specifies the {@link BiFunction} that will be used for renaming all recipes.
     *
     * <p>The first argument to the function is the {@link ResourceLocation} that uniquely identifies its name, whereas
     * the second represents the default name for the recipe according to the default replacement rules. The return
     * value of the function will then represent the new name of the recipe.</p>
     *
     * @param function The renaming function.
     * @return This replacer for chaining
     *
     * @docParam function myFunction
     */
    @ZenCodeType.Method
    public Replacer useForRenaming(final BiFunction<ResourceLocation, String, String> function) {
        if (this.userRenamingFunction != null) {
            final CodePosition position = PositionUtil.getZCScriptPositionFromStackTrace();
            CraftTweakerAPI.logWarning(
                    "%sA renaming function has already been specified for this replacer: the old one will be replaced",
                    position == CodePosition.UNKNOWN? "" : position + ": "
            );
        }
        
        this.userRenamingFunction = function;
        return this;
    }
    
    /**
     * Executes all replacements that have been queued on this replacer, if any.
     */
    @ZenCodeType.Method
    public void execute() {
        if (this.rules.isEmpty()) return;
        CraftTweakerAPI.apply(
                new ReplacerAction(
                        this.targetedManagers,
                        this.targetedRecipes,
                        Collections.unmodifiableList(this.rules),
                        this.targetedManagers.stream().flatMap(manager -> DEFAULT_EXCLUSIONS.computeIfAbsent(manager, this::gatherDefaultExclusions).stream()).collect(Collectors.toSet()),
                        Collections.unmodifiableCollection(this.userExclusionList),
                        this.buildGeneratorFunction()
                )
        );
    }
    
    // Keep public but not exposed to Zen: this is public API (yeah, I know, bad placement)
    public Replacer addReplacementRule(final IReplacementRule rule) {
        if (rule == IReplacementRule.EMPTY) return this;
        this.rules.add(rule);
        return this;
    }
    
    private String fix(final String newName, final ResourceLocation oldName) {
        final CodePosition position = PositionUtil.getZCScriptPositionFromStackTrace();
        return NameUtils.fixing(
                newName,
                (fixed, mistakes) -> CraftTweakerAPI.logWarning(
                        "%sInvalid recipe rename '%s' from '%s', mistakes:\n%s\nThe new rename '%s' will be used",
                        position == CodePosition.UNKNOWN ? "" : position + ": ",
                        newName,
                        oldName,
                        String.join("\n", mistakes),
                        fixed
                )
        );
    }
    
    private Collection<ResourceLocation> gatherDefaultExclusions(final IRecipeManager manager) {
        final GatherReplacementExclusionEvent event = new GatherReplacementExclusionEvent(manager);
        MinecraftForge.EVENT_BUS.post(event);
        return event.getExcludedRecipes();
    }
    
    private Function<ResourceLocation, ResourceLocation> buildGeneratorFunction() {
        if (this.userRenames.isEmpty() && this.userRenamingFunction == null) return DEFAULT_REPLACER;

        final BiFunction<ResourceLocation, String, String> customFunction =
                Optional.ofNullable(this.userRenamingFunction).orElseGet(DEFAULT_CUSTOM_FUNCTION);
        
        final BiFunction<String, String, ResourceLocation> fixer = (custom, original) -> {
            final ResourceLocation originalRl = new ResourceLocation(CraftTweaker.MODID, original);
            
            // Note: the original name will be autogenerated only if it was actually autogenerated. User renames get
            // auto-fixed before they get stored.
            if (custom.equals(original) || NameUtils.isAutogeneratedName(originalRl)) {
                return originalRl;
            }
            
            return NameUtils.fromFixedName(
                    custom,
                    (fixed, mistakes) -> CraftTweakerAPI.logWarning(
                            "Invalid recipe rename '%s' specified in custom renaming function, mistakes:\n%s\nThe new rename will be '%s'",
                            custom,
                            String.join("\n", mistakes),
                            fixed
                    )
            );
        };
        
        return id -> {
            final String original = Optional.ofNullable(this.userRenames.get(id)).orElseGet(() -> DEFAULT_REPLACER.apply(id).getPath());
            return fixer.apply(customFunction.apply(id, original), original);
        };
    }
}
