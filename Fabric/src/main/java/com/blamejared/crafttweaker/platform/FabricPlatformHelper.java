package com.blamejared.crafttweaker.platform;

import com.blamejared.crafttweaker.api.item.IItemStack;
import com.blamejared.crafttweaker.api.item.MCItemStack;
import com.blamejared.crafttweaker.api.item.MCItemStackMutable;
import com.blamejared.crafttweaker.api.loot.LootModifierManager;
import com.blamejared.crafttweaker.api.loot.modifier.ILootModifier;
import com.blamejared.crafttweaker.api.mod.Mod;
import com.blamejared.crafttweaker.api.recipe.handler.helper.CraftingTableRecipeConflictChecker;
import com.blamejared.crafttweaker.api.recipe.manager.base.IRecipeManager;
import com.blamejared.crafttweaker.mixin.common.access.item.AccessBucketItem;
import com.blamejared.crafttweaker.platform.helper.inventory.IInventoryWrapper;
import com.blamejared.crafttweaker.platform.helper.world.inventory.TAInventoryWrapper;
import com.blamejared.crafttweaker.platform.services.IPlatformHelper;
import com.faux.customentitydata.api.CustomDataHelper;
import com.google.common.base.Suppliers;
import com.mojang.datafixers.util.Either;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.PlayerInventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.Util;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.material.Fluid;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.io.File;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FabricPlatformHelper implements IPlatformHelper {
    
    public Supplier<List<Mod>> modList = Suppliers.memoize(() -> FabricLoader.getInstance()
            .getAllMods()
            .stream()
            .map(ModContainer::getMetadata)
            .map(metadata -> new Mod(metadata.getId(), metadata.getName(), metadata.getVersion()
                    .getFriendlyString()))
            .toList());
    
    public Function<String, Optional<Mod>> modFinder = Util.memoize(modid -> modList.get()
            .stream()
            .filter(modObject -> modObject.id().equals(modid))
            .findFirst());
    
    private static final Supplier<Reflections> REFLECTIONS = Suppliers.memoize(FabricPlatformHelper::makeReflections);
    private static final Supplier<MappingResolver> MAPPING_RESOLVER = Suppliers.memoize(() -> FabricLoader.getInstance()
            .getMappingResolver());
    
    private static Reflections makeReflections() {
        
        Collection<URL> urls = ClasspathHelper.forClassLoader();
        // Not a fan of hard coding for a specific thing, but not sure the implications of removing everything that isn't a file.
        // Absolutely hate this but fabric mods gotta be fabric mods, this catches FabricASM and MagicLib
        urls.removeIf(url -> url.getProtocol().contains("magic"));
        return new Reflections(new ConfigurationBuilder().addUrls(urls).setParallel(true));
    }
    
    @Override
    public String getPlatformName() {
        
        return "Fabric";
    }
    
    @Override
    public boolean isModLoaded(String modId) {
        
        return FabricLoader.getInstance().isModLoaded(modId);
    }
    
    @Override
    public boolean isDevelopmentEnvironment() {
        
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }
    
    @Override
    public boolean isDataGen() {
        
        // If / When fabric has a way to detect datagen, update this.
        return false;
    }
    
    @Override
    public List<Mod> getMods() {
        
        return modList.get();
    }
    
    @Override
    public Optional<Mod> getMod(String modid) {
        
        return modFinder.apply(modid);
    }
    
    @Override
    public IItemStack createItemStack(ItemStack stack) {
        
        return new MCItemStack(stack);
    }
    
    @Override
    public IItemStack createItemStackMutable(ItemStack stack) {
        
        return new MCItemStackMutable(stack);
    }
    
    @Override
    public IItemStack getEmptyItemStack() {
        
        return MCItemStack.EMPTY.get();
    }
    
    @Override
    public Fluid getBucketContent(BucketItem item) {
        
        return ((AccessBucketItem) item).crafttweaker$getContent();
    }
    
    @Override
    public Path getGameDirectory() {
        
        return FabricLoader.getInstance().getGameDir();
    }
    
    @Override
    public <T extends Annotation> Stream<? extends Class<?>> findClassesWithAnnotation(
            final Class<T> annotationClass,
            final Consumer<Mod> classProviderConsumer,
            final Predicate<Either<T, Map<String, Object>>> annotationFilter
    ) {
        
        final Set<Class<?>> typesAnnotatedWith = REFLECTIONS.get().getTypesAnnotatedWith(annotationClass);
        return typesAnnotatedWith.stream()
                .filter(it -> it.isAnnotationPresent(annotationClass)) // Thank you reflections for giving classes without the annotation, very cool
                .filter(it -> annotationFilter.test(Either.left(it.getAnnotation(annotationClass))))
                .peek(it -> this.getModsForClass(it).forEach(classProviderConsumer));
    }
    
    private List<Mod> getModsForClass(Class<?> clazz) {
        
        File classFile = new File(clazz.getProtectionDomain().getCodeSource().getLocation().getPath());
        List<Mod> mods = new ArrayList<>();
        // This doesn't work for the current mod in dev.
        // The origin paths just include build/resources/main, not build/classes/main, but otherwise works great
        FabricLoader.getInstance()
                .getAllMods()
                .stream()
                .filter(modContainer -> modContainer.getOrigin().getKind() == ModOrigin.Kind.PATH)
                .forEach(modContainer -> {
                    for(Path path : modContainer.getOrigin().getPaths()) {
                        if(path.toFile().equals(classFile)) {
                            mods.add(new Mod(modContainer.getMetadata().getId(), modContainer.getMetadata()
                                    .getName(), modContainer.getMetadata().getVersion().getFriendlyString()));
                        }
                    }
                });
        return mods;
    }
    
    @Override
    public String findMappedMethodName(final Class<?> clazz, final String methodName, final Class<?> returnType, final Class<?>... parameterTypes) {
        
        final String namespace = MAPPING_RESOLVER.get().getCurrentRuntimeNamespace();
        final String owner = clazz.getName();
        final String descriptor = Arrays.stream(parameterTypes)
                .map(Class::descriptorString)
                .collect(Collectors.joining("", "(", ")" + returnType.descriptorString()));
        return MAPPING_RESOLVER.get().mapMethodName(namespace, owner, methodName, descriptor);
    }
    
    @Override
    public String findMappedFieldName(final Class<?> clazz, final String fieldName, final Class<?> fieldType) {
        
        final String namespace = MAPPING_RESOLVER.get().getCurrentRuntimeNamespace();
        final String owner = clazz.getName();
        final String descriptor = fieldType.descriptorString();
        return MAPPING_RESOLVER.get().mapFieldName(namespace, owner, fieldName, descriptor);
    }
    
    @Override
    public IInventoryWrapper getPlayerInventory(Player player) {
        
        return new TAInventoryWrapper(PlayerInventoryStorage.of(player));
    }
    
    @Override
    public boolean doCraftingTableRecipesConflict(IRecipeManager<?> manager, Recipe<?> first, Recipe<?> second) {
        
        return CraftingTableRecipeConflictChecker.checkConflicts(manager, first, second);
    }
    
    @Override
    public Map<ResourceLocation, ILootModifier> getLootModifiersMap() {
        
        return LootModifierManager.INSTANCE.modifiers();
    }
    
    @SuppressWarnings("UnstableApiUsage")
    @Override
    public Set<MutableComponent> getFluidsForDump(ItemStack stack, Player player, InteractionHand hand) {
        
        Storage<FluidVariant> storage = FluidStorage.ITEM.find(stack, ContainerItemContext.ofPlayerHand(player, InteractionHand.MAIN_HAND));
        if(storage == null) {
            return Set.of();
        }
        Set<MutableComponent> components = new HashSet<>();
        for(StorageView<FluidVariant> view : storage) {
            if(!view.isResourceBlank()) {
                components.add(Component.literal(Registry.FLUID.getKey(view.getResource()
                        .getFluid()) + " * " + view.getAmount()));
            }
        }
        
        return components;
    }
    
    @Override
    public CompoundTag getCustomData(Entity entity) {
        
        return CustomDataHelper.getCustomData(entity);
    }
    
    @Override
    public CompoundTag getPersistentData(ServerPlayer player) {
        
        return CustomDataHelper.getPersistentData(player);
    }
    
}
