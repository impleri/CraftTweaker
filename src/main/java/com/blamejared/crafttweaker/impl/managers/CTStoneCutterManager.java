package com.blamejared.crafttweaker.impl.managers;

import com.blamejared.crafttweaker.CraftTweaker;
import com.blamejared.crafttweaker.api.CraftTweakerAPI;
import com.blamejared.crafttweaker.api.annotations.ZenRegister;
import com.blamejared.crafttweaker.api.item.IIngredient;
import com.blamejared.crafttweaker.api.item.IItemStack;
import com.blamejared.crafttweaker.api.managers.IRecipeManager;
import com.blamejared.crafttweaker.impl.actions.recipes.ActionAddRecipe;
import com.blamejared.crafttweaker_annotations.annotations.Document;
import net.minecraft.item.crafting.IRecipeType;
import net.minecraft.item.crafting.StonecuttingRecipe;
import net.minecraft.util.ResourceLocation;
import org.openzen.zencode.java.ZenCodeGlobals;
import org.openzen.zencode.java.ZenCodeType;

@ZenRegister
@ZenCodeType.Name("crafttweaker.api.StoneCutterManager")
@Document("vanilla/managers/StoneCutterManager")
public class CTStoneCutterManager implements IRecipeManager {
    
    @ZenCodeGlobals.Global("stoneCutter")
    public static final CTStoneCutterManager INSTANCE = new CTStoneCutterManager();
    
    @ZenCodeType.Method
    public void addRecipe(String recipeName, IItemStack output, IIngredient input) {
        CraftTweakerAPI.apply(new ActionAddRecipe(this, new StonecuttingRecipe(new ResourceLocation(CraftTweaker.MODID, recipeName), "", input.asVanillaIngredient(), output.getInternal()), ""));
    }
    
    @Override
    public IRecipeType getRecipeType() {
        return IRecipeType.STONECUTTING;
    }
    
}
