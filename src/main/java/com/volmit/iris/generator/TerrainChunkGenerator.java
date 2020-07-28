package com.volmit.iris.generator;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Bisected.Half;
import org.bukkit.block.data.BlockData;

import com.volmit.iris.layer.GenLayerCave;
import com.volmit.iris.object.DecorationPart;
import com.volmit.iris.object.InferredType;
import com.volmit.iris.object.IrisBiome;
import com.volmit.iris.object.IrisBiomeDecorator;
import com.volmit.iris.object.IrisRegion;
import com.volmit.iris.object.atomics.AtomicSliver;
import com.volmit.iris.util.BiomeMap;
import com.volmit.iris.util.BiomeResult;
import com.volmit.iris.util.BlockDataTools;
import com.volmit.iris.util.CaveResult;
import com.volmit.iris.util.HeightMap;
import com.volmit.iris.util.KList;
import com.volmit.iris.util.M;
import com.volmit.iris.util.RNG;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public abstract class TerrainChunkGenerator extends ParallelChunkGenerator
{
	private long lastUpdateRequest = M.ms();
	private long lastChunkLoad = M.ms();
	private GenLayerCave glCave;
	private RNG rockRandom;

	public TerrainChunkGenerator(String dimensionName, int threads)
	{
		super(dimensionName, threads);
	}

	public void onInit(World world, RNG rng)
	{
		super.onInit(world, rng);
		rockRandom = getMasterRandom().nextParallelRNG(2858678);
		glCave = new GenLayerCave(this, rng.nextParallelRNG(238948));
	}

	public KList<CaveResult> getCaves(int x, int z)
	{
		return glCave.genCaves(x, z, x & 15, z & 15, null);
	}

	@Override
	protected void onGenerateColumn(int cx, int cz, int rx, int rz, int x, int z, AtomicSliver sliver, BiomeMap biomeMap)
	{
		try
		{
			BlockData block;
			int fluidHeight = getDimension().getFluidHeight();
			double ox = getModifiedX(rx, rz);
			double oz = getModifiedZ(rx, rz);
			double wx = getZoomed(ox);
			double wz = getZoomed(oz);
			int depth = 0;
			double noise = getNoiseHeight(rx, rz);
			int height = (int) Math.round(noise) + fluidHeight;
			IrisRegion region = sampleRegion(rx, rz);
			IrisBiome biome = sampleTrueBiome(rx, rz).getBiome();
			KList<BlockData> layers = biome.generateLayers(wx, wz, masterRandom, height, height - getFluidHeight());
			KList<BlockData> seaLayers = biome.isSea() ? biome.generateSeaLayers(wx, wz, masterRandom, fluidHeight - height) : new KList<>();
			cacheBiome(x, z, biome);

			// Set ground biome (color) to HEIGHT - HEIGHT+3
			for(int k = Math.max(height, fluidHeight); k < Math.max(height, fluidHeight) + 3; k++)
			{
				if(k < Math.max(height, fluidHeight) + 3)
				{
					if(biomeMap != null)
					{
						sliver.set(k, biome.getGroundBiome(masterRandom, rz, k, rx));
					}
				}
			}

			// Set Biomes & Blocks from HEIGHT/FLUIDHEIGHT to 0
			for(int k = Math.max(height, fluidHeight); k >= 0; k--)
			{
				if(k == 0)
				{
					sliver.set(0, BEDROCK);
					continue;
				}

				boolean underwater = k > height && k <= fluidHeight;

				if(biomeMap != null)
				{
					sliver.set(k, biome.getGroundBiome(masterRandom, rz, k, rx));
					biomeMap.setBiome(x, z, biome);
				}

				if(underwater)
				{
					block = seaLayers.hasIndex(fluidHeight - k) ? layers.get(depth) : getDimension().getFluid(rockRandom, wx, k, wz);
				}

				else
				{
					block = layers.hasIndex(depth) ? layers.get(depth) : getDimension().getRock(rockRandom, wx, k, wz);
					depth++;
				}

				sliver.set(k, block);

				// Decorate underwater
				if(k == height && block.getMaterial().isSolid() && k < fluidHeight)
				{
					decorateUnderwater(biome, sliver, wx, k, wz, rx, rz, block);
				}

				// Decorate land
				if(k == Math.max(height, fluidHeight) && block.getMaterial().isSolid() && k < 255 && k > fluidHeight)
				{
					decorateLand(biome, sliver, wx, k, wz, rx, rz, block);
				}
			}

			KList<CaveResult> caveResults = glCave.genCaves(rx, rz, x, z, sliver);
			IrisBiome caveBiome = glBiome.generateData(InferredType.CAVE, wx, wz, rx, rz, region).getBiome();

			if(caveBiome != null)
			{
				for(CaveResult i : caveResults)
				{
					for(int j = i.getFloor(); j <= i.getCeiling(); j++)
					{
						sliver.set(j, caveBiome);
						sliver.set(j, caveBiome.getGroundBiome(masterRandom, rz, j, rx));
					}

					KList<BlockData> floor = caveBiome.generateLayers(wx, wz, rockRandom, i.getFloor() - 2, i.getFloor() - 2);
					KList<BlockData> ceiling = caveBiome.generateLayers(wx + 256, wz + 256, rockRandom, height - i.getCeiling() - 2, height - i.getCeiling() - 2);
					BlockData blockc = null;
					for(int j = 0; j < floor.size(); j++)
					{
						if(j == 0)
						{
							blockc = floor.get(j);
						}

						sliver.set(i.getFloor() - j, floor.get(j));
					}

					for(int j = ceiling.size() - 1; j > 0; j--)
					{
						sliver.set(i.getCeiling() + j, ceiling.get(j));
					}

					if(blockc != null && !sliver.isSolid(i.getFloor() + 1))
					{
						decorateCave(caveBiome, sliver, wx, i.getFloor(), wz, rx, rz, blockc);
					}
				}
			}
		}

		catch(Throwable e)
		{
			fail(e);
		}
	}

	protected boolean canPlace(Material mat, Material onto)
	{
		if(onto.equals(Material.GRASS_PATH))
		{
			if(!mat.isSolid())
			{
				return false;
			}
		}

		if(onto.equals(Material.ACACIA_LEAVES) || onto.equals(Material.BIRCH_LEAVES) || onto.equals(Material.DARK_OAK_LEAVES) || onto.equals(Material.JUNGLE_LEAVES) || onto.equals(Material.OAK_LEAVES) || onto.equals(Material.SPRUCE_LEAVES))
		{
			if(!mat.isSolid())
			{
				return false;
			}
		}

		return true;
	}

	private void decorateLand(IrisBiome biome, AtomicSliver sliver, double wx, int k, double wz, int rx, int rz, BlockData block)
	{
		if(!getDimension().isDecorate())
		{
			return;
		}

		int j = 0;

		for(IrisBiomeDecorator i : biome.getDecorators())
		{
			if(i.getPartOf().equals(DecorationPart.SHORE_LINE) && !touchesSea(rx, rz))
			{
				continue;
			}

			BlockData d = i.getBlockData(getMasterRandom().nextParallelRNG(biome.hashCode() + j++), wx, wz);

			if(d != null)
			{
				if(!canPlace(d.getMaterial(), block.getMaterial()))
				{
					continue;
				}

				if(d.getMaterial().equals(Material.CACTUS))
				{
					if(!block.getMaterial().equals(Material.SAND) && !block.getMaterial().equals(Material.RED_SAND))
					{
						sliver.set(k, BlockDataTools.getBlockData("RED_SAND"));
					}
				}

				if(d instanceof Bisected && k < 254)
				{
					Bisected t = ((Bisected) d.clone());
					t.setHalf(Half.TOP);
					Bisected b = ((Bisected) d.clone());
					b.setHalf(Half.BOTTOM);
					sliver.set(k + 1, b);
					sliver.set(k + 2, t);
				}

				else
				{
					int stack = i.getHeight(getMasterRandom().nextParallelRNG(39456 + i.hashCode()), wx, wz);

					if(stack == 1)
					{
						sliver.set(k + 1, d);
					}

					else if(k < 255 - stack)
					{
						for(int l = 0; l < stack; l++)
						{
							sliver.set(k + l + 1, d);
						}
					}
				}

				break;
			}
		}
	}

	private void decorateCave(IrisBiome biome, AtomicSliver sliver, double wx, int k, double wz, int rx, int rz, BlockData block)
	{
		if(!getDimension().isDecorate())
		{
			return;
		}

		int j = 0;

		for(IrisBiomeDecorator i : biome.getDecorators())
		{
			BlockData d = i.getBlockData(getMasterRandom().nextParallelRNG(biome.hashCode() + j++), wx, wz);

			if(d != null)
			{
				if(!canPlace(d.getMaterial(), block.getMaterial()))
				{
					continue;
				}

				if(d.getMaterial().equals(Material.CACTUS))
				{
					if(!block.getMaterial().equals(Material.SAND) && !block.getMaterial().equals(Material.RED_SAND))
					{
						sliver.set(k, BlockDataTools.getBlockData("SAND"));
					}
				}

				if(d instanceof Bisected && k < 254)
				{
					Bisected t = ((Bisected) d.clone());
					t.setHalf(Half.TOP);
					Bisected b = ((Bisected) d.clone());
					b.setHalf(Half.BOTTOM);
					sliver.set(k + 1, b);
					sliver.set(k + 2, t);
				}

				else
				{
					int stack = i.getHeight(getMasterRandom().nextParallelRNG(39456 + i.hashCode()), wx, wz);

					if(stack == 1)
					{
						sliver.set(k + 1, d);
					}

					else if(k < 255 - stack)
					{
						for(int l = 0; l < stack; l++)
						{
							if(sliver.isSolid(k + l + 1))
							{
								break;
							}

							sliver.set(k + l + 1, d);
						}
					}
				}

				break;
			}
		}
	}

	private void decorateUnderwater(IrisBiome biome, AtomicSliver sliver, double wx, int y, double wz, int rx, int rz, BlockData block)
	{
		if(!getDimension().isDecorate())
		{
			return;
		}

		int j = 0;

		for(IrisBiomeDecorator i : biome.getDecorators())
		{
			if(biome.getInferredType().equals(InferredType.SHORE))
			{
				continue;
			}

			BlockData d = i.getBlockData(getMasterRandom().nextParallelRNG(biome.hashCode() + j++), wx, wz);

			if(d != null)
			{
				int stack = i.getHeight(getMasterRandom().nextParallelRNG(39456 + i.hashCode()), wx, wz);

				if(stack == 1)
				{
					sliver.set(i.getPartOf().equals(DecorationPart.SEA_SURFACE) ? (getFluidHeight() + 1) : (y + 1), d);
				}

				else if(y < getFluidHeight() - stack)
				{
					for(int l = 0; l < stack; l++)
					{
						sliver.set(i.getPartOf().equals(DecorationPart.SEA_SURFACE) ? (getFluidHeight() + 1 + l) : (y + l + 1), d);
					}
				}

				break;
			}
		}
	}

	@Override
	protected void onPostGenerate(RNG random, int x, int z, ChunkData data, BiomeGrid grid, HeightMap height, BiomeMap biomeMap)
	{
		onPreParallaxPostGenerate(random, x, z, data, grid, height, biomeMap);
	}

	protected void onPreParallaxPostGenerate(RNG random, int x, int z, ChunkData data, BiomeGrid grid, HeightMap height, BiomeMap biomeMap)
	{

	}

	protected void onPostParallaxPostGenerate(RNG random, int x, int z, ChunkData data, BiomeGrid grid, HeightMap height, BiomeMap biomeMap)
	{

	}

	protected double getNoiseHeight(int rx, int rz)
	{
		double wx = getZoomed(rx);
		double wz = getZoomed(rz);

		return getBiomeHeight(wx, wz);
	}

	public BiomeResult sampleTrueBiomeBase(int x, int z)
	{
		if(!getDimension().getFocus().equals(""))
		{
			return focus();
		}

		double wx = getModifiedX(x, z);
		double wz = getModifiedZ(x, z);
		IrisRegion region = sampleRegion(x, z);
		int height = (int) Math.round(getTerrainHeight(x, z));
		double sh = region.getShoreHeight(wx, wz);
		IrisBiome current = sampleBiome(x, z).getBiome();

		if(current.isShore() && height > sh)
		{
			return glBiome.generateData(InferredType.LAND, wx, wz, x, z, region);
		}

		if(current.isShore() || current.isLand() && height <= getDimension().getFluidHeight())
		{
			return glBiome.generateData(InferredType.SEA, wx, wz, x, z, region);
		}

		if(current.isSea() && height > getDimension().getFluidHeight())
		{
			return glBiome.generateData(InferredType.LAND, wx, wz, x, z, region);
		}

		if(height <= getDimension().getFluidHeight())
		{
			return glBiome.generateData(InferredType.SEA, wx, wz, x, z, region);
		}

		if(height <= getDimension().getFluidHeight() + sh)
		{
			return glBiome.generateData(InferredType.SHORE, wx, wz, x, z, region);
		}

		return glBiome.generateRegionData(wx, wz, x, z, region);
	}

	public BiomeResult sampleCaveBiome(int x, int z)
	{
		double wx = getModifiedX(x, z);
		double wz = getModifiedZ(x, z);
		return glBiome.generateData(InferredType.CAVE, wx, wz, x, z, sampleRegion(x, z));
	}

	public BiomeResult sampleTrueBiome(int x, int y, int z)
	{
		if(y < getTerrainHeight(x, z))
		{
			double wx = getModifiedX(x, z);
			double wz = getModifiedZ(x, z);
			BiomeResult r = glBiome.generateData(InferredType.CAVE, wx, wz, x, z, sampleRegion(x, z));

			if(r.getBiome() != null)
			{
				return r;
			}
		}

		return sampleTrueBiome(x, z);
	}

	public BiomeResult sampleTrueBiome(int x, int z)
	{
		if(!getDimension().getFocus().equals(""))
		{
			return focus();
		}

		double wx = getModifiedX(x, z);
		double wz = getModifiedZ(x, z);
		IrisRegion region = sampleRegion(x, z);
		int height = sampleHeight(x, z);
		double sh = region.getShoreHeight(wx, wz);
		BiomeResult res = sampleTrueBiomeBase(x, z);
		IrisBiome current = res.getBiome();

		if(current.isSea() && height > getDimension().getFluidHeight() - sh)
		{
			return glBiome.generateData(InferredType.SHORE, wx, wz, x, z, region);
		}

		return res;
	}

	@Override
	protected int onSampleColumnHeight(int cx, int cz, int rx, int rz, int x, int z)
	{
		int fluidHeight = getDimension().getFluidHeight();
		double noise = getNoiseHeight(rx, rz);

		return (int) Math.round(noise) + fluidHeight;
	}

	private boolean touchesSea(int rx, int rz)
	{
		return isFluidAtHeight(rx + 1, rz) || isFluidAtHeight(rx - 1, rz) || isFluidAtHeight(rx, rz - 1) || isFluidAtHeight(rx, rz + 1);
	}

	public boolean isUnderwater(int x, int z)
	{
		return isFluidAtHeight(x, z);
	}

	public boolean isFluidAtHeight(int x, int z)
	{
		return Math.round(getTerrainHeight(x, z)) < getFluidHeight();
	}

	public int getFluidHeight()
	{
		return getDimension().getFluidHeight();
	}

	public double getTerrainHeight(int x, int z)
	{
		return getNoiseHeight(x, z) + getFluidHeight();
	}

	public double getTerrainWaterHeight(int x, int z)
	{
		return Math.max(getTerrainHeight(x, z), getFluidHeight());
	}
}
