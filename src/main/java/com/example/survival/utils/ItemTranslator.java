package com.example.survival.utils;

import xin.bbtt.inventory.ItemRegistry;
import java.util.HashMap;
import java.util.Map;

/**
 * 物品名称双语翻译器 (English / 中文)
 */
public class ItemTranslator {

    private static final Map<String, String> ZH_MAP = new HashMap<>();

    static {
        put("Oak Planks", "橡木木板");
        put("Spruce Planks", "云杉木板");
        put("Birch Planks", "白桦木板");
        put("Jungle Planks", "丛林木板");
        put("Acacia Planks", "金合欢木板");
        put("Dark Oak Planks", "深色橡木木板");
        put("Cobblestone", "圆石");
        put("Stone", "石头");
        put("Dirt", "泥土");
        put("Grass Block", "草方块");
        put("Sand", "沙子");
        put("Gravel", "沙砾");
        put("Oak Log", "橡木原木");
        put("Oak Leaves", "橡木树叶");
        put("Glass", "玻璃");
        put("TNT", "TNT");
        put("Torch", "火把");
        put("Soul Torch", "灵魂火把");
        put("Ladder", "梯子");
        put("Chest", "箱子");
        put("Ender Chest", "末影箱");
        put("Furnace", "熔炉");
        put("Crafting Table", "工作台");
        put("Anvil", "铁砧");
        put("Bedrock", "基岩");
        put("Obsidian", "黑曜石");
        put("Crying Obsidian", "哭泣的黑曜石");
        put("Iron Block", "铁块");
        put("Gold Block", "金块");
        put("Diamond Block", "钻石块");
        put("Netherrack", "下界岩");
        put("End Stone", "末地石");
        put("Soul Sand", "灵魂沙");
        put("Ice", "冰");
        put("Packed Ice", "浮冰");
        put("Snow Block", "雪块");
        put("Coal", "煤炭");
        put("Coal Ore", "煤矿石");
        put("Iron Ore", "铁矿石");
        put("Gold Ore", "金矿石");
        put("Diamond Ore", "钻石矿石");
        put("Lapis Lazuli Ore", "青金石矿石");
        put("Redstone Ore", "红石矿石");
        put("Emerald Ore", "绿宝石矿石");
        put("Copper Ore", "铜矿石");
        put("Iron Ingot", "铁锭");
        put("Gold Ingot", "金锭");
        put("Diamond", "钻石");
        put("Emerald", "绿宝石");
        put("Lapis Lazuli", "青金石");
        put("Redstone", "红石");
        put("Netherite Ingot", "下界合金锭");
        put("Netherite Scrap", "下界合金碎片");
        put("Ancient Debris", "远古残骸");
        put("Copper Ingot", "铜锭");
        put("Raw Iron", "粗铁");
        put("Raw Gold", "粗金");
        put("Raw Copper", "粗铜");
        put("Amethyst Shard", "紫水晶碎片");
        put("Quartz", "石英");
        put("Prismarine Shard", "海晶碎片");
        put("Prismarine Crystals", "海晶砂粒");
        put("Nether Star", "下界之星");
        put("Elytra", "鞘翅");

        put("Apple", "苹果");
        put("Golden Apple", "金苹果");
        put("Enchanted Golden Apple", "附魔金苹果");
        put("Golden Carrot", "金胡萝卜");
        put("Bread", "面包");
        put("Cooked Beef", "熟牛排");
        put("Cooked Porkchop", "熟猪排");
        put("Cooked Mutton", "熟羊肉");
        put("Cooked Chicken", "熟鸡肉");
        put("Cooked Cod", "熟鳕鱼");
        put("Cooked Salmon", "熟鲑鱼");
        put("Raw Beef", "生牛肉");
        put("Raw Porkchop", "生猪排");
        put("Rotten Flesh", "腐肉");
        put("Spider Eye", "蜘蛛眼");
        put("Melon Slice", "西瓜片");
        put("Cookie", "曲奇");
        put("Cake", "蛋糕");
        put("Mushroom Stew", "蘑菇煲");

        put("Stick", "木棍");
        put("String", "线");
        put("Feather", "羽毛");
        put("Leather", "皮革");
        put("Paper", "纸");
        put("Book", "书");
        put("Slime Ball", "粘液球");
        put("Blaze Rod", "烈焰棒");
        put("Blaze Powder", "烈焰粉");
        put("Ender Pearl", "末影珍珠");
        put("Ender Eye", "末影之眼");
        put("Ghast Tear", "恶魂之泪");
        put("Magma Cream", "岩浆膏");
        put("Bone", "骨头");
        put("Bone Meal", "骨粉");
        put("Gunpowder", "火药");
        put("Ink Sac", "墨囊");
        put("Glow Ink Sac", "荧光墨囊");
        put("Sugar", "糖");
        put("Wheat", "小麦");
        put("Seeds", "种子");
        put("Carrot", "胡萝卜");
        put("Potato", "马铃薯");
        put("Beetroot", "甜菜根");
        put("Iron Nugget", "铁粒");
        put("Gold Nugget", "金粒");
        put("Brick", "红砖");
        put("Nether Brick", "下界砖");
        put("Clay Ball", "粘土球");
        put("Snowball", "雪球");
        put("Fire Charge", "火焰弹");
        put("Bottle o' Enchanting", "附魔之瓶");

        put("Potion", "药水");
        put("Splash Potion", "喷溅药水");
        put("Lingering Potion", "滞留药水");
        put("Glass Bottle", "玻璃瓶");
        put("Bucket", "桶");
        put("Water Bucket", "水桶");
        put("Lava Bucket", "岩浆桶");
        put("Milk Bucket", "牛奶桶");
        put("Totem of Undying", "不死图腾");
        put("Compass", "指南针");
        put("Clock", "时钟");
        put("Map", "地图");
        put("Name Tag", "命名牌");
        put("Lead", "拴绳");
        put("Saddle", "鞍");
        put("Arrow", "箭");
        put("Spectral Arrow", "光灵箭");
        put("Firework Rocket", "烟花火箭");

        put("Redstone Torch", "红石火把");
        put("Repeater", "中继器");
        put("Comparator", "比较器");
        put("Piston", "活塞");
        put("Sticky Piston", "粘性活塞");
        put("Observer", "侦测器");
        put("Dispenser", "发射器");
        put("Dropper", "投掷器");
        put("Hopper", "漏斗");
        put("Lever", "拉杆");
        put("Button", "按钮");
        put("Redstone Lamp", "红石灯");
        put("Note Block", "音符盒");
        put("Jukebox", "唱片机");
        put("Minecart", "矿车");
        put("Rail", "铁轨");
        put("Powered Rail", "动力铁轨");
        put("Detector Rail", "探测铁轨");

        put("Leather Helmet", "皮革头盔");
        put("Leather Chestplate", "皮革胸甲");
        put("Leather Leggings", "皮革护腿");
        put("Leather Boots", "皮革靴子");
        put("Iron Helmet", "铁头盔");
        put("Iron Chestplate", "铁胸甲");
        put("Iron Leggings", "铁护腿");
        put("Iron Boots", "铁靴子");
        put("Diamond Helmet", "钻石头盔");
        put("Diamond Chestplate", "钻石胸甲");
        put("Diamond Leggings", "钻石护腿");
        put("Diamond Boots", "钻石靴子");
        put("Netherite Helmet", "下界合金头盔");
        put("Netherite Chestplate", "下界合金胸甲");
        put("Netherite Leggings", "下界合金护腿");
        put("Netherite Boots", "下界合金靴子");
        put("Shield", "盾牌");
        put("Turtle Shell", "海龟壳");

        put("Wooden Sword", "木剑");
        put("Wooden Pickaxe", "木镐");
        put("Wooden Axe", "木斧");
        put("Wooden Shovel", "木锹");
        put("Wooden Hoe", "木锄");
        put("Stone Sword", "石剑");
        put("Stone Pickaxe", "石镐");
        put("Stone Axe", "石斧");
        put("Stone Shovel", "石锹");
        put("Stone Hoe", "石锄");
        put("Iron Sword", "铁剑");
        put("Iron Pickaxe", "铁镐");
        put("Iron Axe", "铁斧");
        put("Iron Shovel", "铁锹");
        put("Iron Hoe", "铁锄");
        put("Diamond Sword", "钻石剑");
        put("Diamond Pickaxe", "钻石镐");
        put("Diamond Axe", "钻石斧");
        put("Diamond Shovel", "钻石锹");
        put("Diamond Hoe", "钻石锄");
        put("Netherite Sword", "下界合金剑");
        put("Netherite Pickaxe", "下界合金镐");
        put("Netherite Axe", "下界合金斧");
        put("Netherite Shovel", "下界合金锹");
        put("Netherite Hoe", "下界合金锄");
        put("Bow", "弓");
        put("Crossbow", "弩");
        put("Trident", "三叉戟");
        put("Fishing Rod", "钓鱼竿");
        put("Shears", "剪刀");
        put("Flint and Steel", "打火石");
        put("Bone Block", "骨块");
        put("Dried Kelp Block", "干海带块");
        put("Honey Block", "蜂蜜块");
        put("Honeycomb Block", "蜜脾块");
        put("Smooth Stone", "平滑石头");
        put("Mossy Cobblestone", "苔石");
        put("Deepslate", "深板岩");
        put("Cobbled Deepslate", "深板岩圆石");
        put("Polished Deepslate", "磨制深板岩");
        put("Deepslate Tiles", "深板岩瓦");
        put("Deepslate Bricks", "深板岩砖");
        put("Tuff", "凝灰岩");
        put("Calcite", "方解石");
        put("Dripstone Block", "滴水石块");
        put("Pointed Dripstone", "钟乳石");
        put("Magma Block", "岩浆块");
        put("Glowstone", "荧石");
        put("Sea Lantern", "海晶灯");
        put("Shroomlight", "菌光体");
        put("Hay Bale", "干草捆");
        put("Target", "标靶");
        put("Smithing Table", "锻造台");
        put("Grindstone", "砂轮");
        put("Loom", "织布机");
        put("Barrel", "木桶");
        put("Blast Furnace", "高炉");
        put("Smoker", "烟熏炉");
        put("Campfire", "营火");
        put("Soul Campfire", "灵魂营火");
        put("Respawn Anchor", "重生锚");
        put("Enchanting Table", "附魔台");
        put("Brewing Stand", "酿造台");
        put("Cauldron", "炼药锅");
        put("Bell", "钟");
        put("Beacon", "信标");
        put("Conduit", "潮涌核心");
        put("End Portal Frame", "末地传送门框架");
        put("Dragon Egg", "龙蛋");
        put("Item Frame", "物品展示框");
        put("Glow Item Frame", "荧光物品展示框");
        put("Painting", "画");
        put("Armor Stand", "盔甲架");
        put("Flower Pot", "花盆");
        put("Bookshelf", "书架");
        put("Lectern", "讲台");
        put("Composter", "堆肥桶");
        put("Stonecutter", "切石机");
        put("Cartography Table", "制图台");
        put("Fletching Table", "制箭台");
        put("Honeycomb", "蜜脾");
        put("Scaffolding", "脚手架");
        put("Chain", "锁链");
        put("Lantern", "灯笼");
        put("Soul Lantern", "灵魂灯笼");
        put("Candle", "蜡烛");
        put("Amethyst Cluster", "紫水晶簇");
        put("Spyglass", "望远镜");
        put("Bundle", "收纳袋");
        put("Goat Horn", "山羊角");
        put("Brush", "刷子");
        put("Recovery Compass", "追溯指针");

        put("Golden Sword", "金剑");
        put("Golden Pickaxe", "金镐");
        put("Golden Axe", "金斧");
        put("Golden Shovel", "金锹");
        put("Golden Hoe", "金锄");
        put("Chainmail Helmet", "锁链头盔");
        put("Chainmail Chestplate", "锁链胸甲");
        put("Chainmail Leggings", "锁链护腿");
        put("Chainmail Boots", "锁链靴子");
        put("Golden Helmet", "金头盔");
        put("Golden Chestplate", "金胸甲");
        put("Golden Leggings", "金护腿");
        put("Golden Boots", "金靴子");
        put("Leather Horse Armor", "皮革马铠");
        put("Iron Horse Armor", "铁马铠");
        put("Diamond Horse Armor", "钻石马铠");
        put("Golden Horse Armor", "金马铠");
    }

    private static void put(String en, String zh) {
        ZH_MAP.put(en, zh);
    }

    public static String toEnglish(int itemId) {
        ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(itemId);
        if (entry != null && entry.getDisplayName() != null) {
            return entry.getDisplayName();
        }
        return "Unknown (ID:" + itemId + ")";
    }

    public static String toChinese(int itemId) {
        ItemRegistry.ItemEntry entry = ItemRegistry.Instance.getItem(itemId);
        if (entry == null) return "未知物品(ID:" + itemId + ")";
        String en = entry.getDisplayName();
        String zh = ZH_MAP.get(en);
        return zh != null ? zh : en;
    }

    public static String toBilingual(int itemId) {
        String en = toEnglish(itemId);
        String zh = ZH_MAP.get(en);
        return zh != null ? en + " / " + zh : en;
    }

    public static String toBilingualName(String englishName) {
        String zh = ZH_MAP.get(englishName);
        return zh != null ? englishName + " / " + zh : englishName;
    }
}
