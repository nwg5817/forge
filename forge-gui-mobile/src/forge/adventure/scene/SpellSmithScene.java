package forge.adventure.scene;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Array;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;
import forge.StaticData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.RewardData;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.util.*;
import forge.card.CardEdition;
import forge.card.ColorSet;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.util.MyRandom;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;


public class SpellSmithScene extends UIScene {

    private static SpellSmithScene object;

    // Method that accepts PointOfInterestChanges
    public static SpellSmithScene instance(PointOfInterestChanges localChanges) {
        changes = localChanges;

        if (object == null)
            object = new SpellSmithScene();
        return object;
    }

    // Overloaded method without PointOfInterestChanges
    public static SpellSmithScene instance() {
        if (object == null)
            object = new SpellSmithScene();
        return object;
    }

    private List<PaperCard> cardPool = new ArrayList<>();
    private TextraLabel playerGold, playerShards, poolSize;
    private final TextraButton pullUsingGold, pullPackUsingGold, pullUsingShards, acceptReward, declineReward, exitSmith;
    private final ScrollPane rewardDummy;
    private RewardActor rewardActor;
    SelectBox<CardEdition> editionList;
    //Button containers.
    final private HashMap<String, TextraButton> rarityButtons = new HashMap<>();
    final private HashMap<String, TextraButton> costButtons = new HashMap<>();
    final private HashMap<String, TextraButton> colorButtons = new HashMap<>();

    //Filter variables.
    private String edition = "";
    private String rarity = "";
    private int cost_low = -1;
    private int cost_high = 9999;
    //Other
    private final float basePrice = 125f;
    private int currentPrice = 0;
    private int currentShardPrice = 0;
    private List<CardEdition> editions = null;
    private Reward currentReward = null;
    private boolean paidInShards = false;
    static PointOfInterestChanges changes;
    
    private SpellSmithScene() {
        super(Forge.isLandscapeMode() ? "ui/spellsmith.json" : "ui/spellsmith_portrait.json");

        editionList = ui.findActor("BSelectPlane");
        rewardDummy = ui.findActor("RewardDummy");
        rewardDummy.setVisible(false);

        pullUsingGold = ui.findActor("pullUsingGold");
        pullUsingGold.setDisabled(true);

        pullPackUsingGold = ui.findActor("pullPackUsingGold");
        pullPackUsingGold.setDisabled(true);

        pullUsingShards = ui.findActor("pullUsingShards");
        pullUsingShards.setDisabled(true);

        exitSmith = ui.findActor("done");

        acceptReward = ui.findActor("accept");
        acceptReward.setVisible(false);
        declineReward = ui.findActor("decline");
        declineReward.setVisible(false);

        playerGold = Controls.newAccountingLabel(ui.findActor("playerGold"), false);
        playerShards = Controls.newAccountingLabel(ui.findActor("playerShards"), true);
        poolSize = ui.findActor("poolSize");
        for (String i : new String[]{"BBlack", "BBlue", "BGreen", "BRed", "BWhite", "BColorless"}) {
            TextraButton button = ui.findActor(i);
            if (button != null) {
                colorButtons.put(i, button);
                button.addListener(new ClickListener() {
                    @Override
                    public void clicked(InputEvent event, float x, float y) {
                        selectColor(i);
                        filterResults();
                    }
                });
            }
        }
        for (String i : new String[]{"BCommon", "BUncommon", "BRare", "BMythic"}) {
            TextraButton button = ui.findActor(i);
            if (button != null) {
                rarityButtons.put(i, button);
                button.addListener(new ClickListener() {
                    @Override
                    public void clicked(InputEvent event, float x, float y) {
                        if (selectRarity(i)) button.setColor(Color.RED);
                        filterResults();
                    }
                });
            }
        }
        for (String i : new String[]{"B02", "B35", "B68", "B9X"}) {
            TextraButton button = ui.findActor(i);
            if (button != null) {
                costButtons.put(i, button);
                button.addListener(new ClickListener() {
                    @Override
                    public void clicked(InputEvent event, float x, float y) {
                        if (selectCost(i)) button.setColor(Color.RED);
                        filterResults();
                    }
                });
            }
        }

        ui.onButtonPress("accept", SpellSmithScene.this::acceptSmithing);
        ui.onButtonPress("decline", SpellSmithScene.this::declineSmithing);
        ui.onButtonPress("done", SpellSmithScene.this::done);
        ui.onButtonPress("pullUsingGold", () -> SpellSmithScene.this.pullCard(false));
        ui.onButtonPress("pullPackUsingGold", () -> SpellSmithScene.this.pullPack(false));
        ui.onButtonPress("pullUsingShards", () -> SpellSmithScene.this.pullCard(true));
        ui.onButtonPress("BReset", () -> {
            reset();
            filterResults();
        });
    }

    private void reset() {
        edition = "";
        cost_low = -1;
        cost_high = 9999;
        rarity = "";
        currentPrice = (int) basePrice;
        for (Map.Entry<String, TextraButton> B : colorButtons.entrySet()) B.getValue().setColor(Color.WHITE);
        for (Map.Entry<String, TextraButton> B : costButtons.entrySet()) B.getValue().setColor(Color.WHITE);
        for (Map.Entry<String, TextraButton> B : rarityButtons.entrySet()) B.getValue().setColor(Color.WHITE);
        editionList.setColor(Color.WHITE);
        editionList.setUserObject(edition);
    }

    public void loadEditions() {
        if (editions != null)
            return;
        editions = StaticData.instance().getSortedEditions().stream().filter(input -> {
            if (input == null)
                return false;
            if (input.getType() != CardEdition.Type.CORE && input.getType() != CardEdition.Type.EXPANSION && input.getType() != CardEdition.Type.BOXED_SET && input.getType() != CardEdition.Type.DRAFT)
                return false;
            if (input.getDate() != null) {
                Instant now = Instant.now(); //this should filter upcoming sets from release date + 1 day..
                if (input.getDate().after(Date.from(now.minus(1, ChronoUnit.DAYS))))
                    return false;
            }
            List<PaperCard> it = StreamSupport.stream(RewardData.getAllCards().spliterator(), false)
                    .filter(input2 -> input2.getEdition().equals(input.getCode())).collect(Collectors.toList());
            if (it.isEmpty())
                return false;
            ConfigData configData = Config.instance().getConfigData();
            if (configData.allowedEditions != null)
                return Arrays.asList(configData.allowedEditions).contains(input.getCode());
            return (!Arrays.asList(configData.restrictedEditions).contains(input.getCode()));
        }).sorted(Comparator.comparing(CardEdition::getName)).collect(Collectors.toList());
    }

    public boolean done() {
        if (currentReward != null) {
            acceptSmithing();
        }

        if (rewardActor != null) rewardActor.remove();
        cardPool.clear(); //Get rid of cardPool, filtering is fast enough to justify keeping it cached.
        Forge.switchToLast();
        return true;
    }

    private boolean selectRarity(String what) {
        for (Map.Entry<String, TextraButton> B : rarityButtons.entrySet())
            B.getValue().setColor(Color.WHITE);
        switch (what) {
            case "BCommon":
                if (rarity.equals("C")) {
                    rarity = "";
                    return false;
                }
                rarity = "C";
                break;
            case "BUncommon":
                if (rarity.equals("U")) {
                    rarity = "";
                    return false;
                }
                rarity = "U";
                break;
            case "BRare":
                if (rarity.equals("R")) {
                    rarity = "";
                    return false;
                }
                rarity = "R";
                break;
            case "BMythic":
                if (rarity.equals("M")) {
                    rarity = "";
                    return false;
                }
                rarity = "M";
                break;
            default:
                rarity = "";
                break;
        }
        return true;
    }

    private void selectColor(String what) {
        TextraButton B = colorButtons.get(what);
        switch (what) {
            case "BColorless":
                if (B.getColor().equals(Color.RED)) B.setColor(Color.WHITE);
                else {
                    for (Map.Entry<String, TextraButton> BT : colorButtons.entrySet())
                        BT.getValue().setColor(Color.WHITE);
                    B.setColor(Color.RED);
                }
                break;
            case "BBlack":
            case "BBlue":
            case "BGreen":
            case "BRed":
            case "BWhite":
                if (B.getColor().equals(Color.RED)) B.setColor(Color.WHITE);
                else B.setColor(Color.RED);
                break;

        }
    }

    private boolean selectCost(String what) {
        for (Map.Entry<String, TextraButton> B : costButtons.entrySet())
            B.getValue().setColor(Color.WHITE);
        switch (what) {
            case "B02":
                if (cost_low == 0 && cost_high == 2) {
                    cost_low = -1;
                    cost_high = 9999;
                    return false;
                }
                cost_low = 0;
                cost_high = 2;
                break;
            case "B35":
                if (cost_low == 3 && cost_high == 5) {
                    cost_low = -1;
                    cost_high = 9999;
                    return false;
                }
                cost_low = 3;
                cost_high = 5;
                break;
            case "B68":
                if (cost_low == 6 && cost_high == 8) {
                    cost_low = -1;
                    cost_high = 9999;
                    return false;
                }
                cost_low = 6;
                cost_high = 8;
                break;
            case "B9X":
                if (cost_low == 9 && cost_high == 9999) {
                    cost_low = -1;
                    cost_high = 9999;
                    return false;
                }
                cost_low = 9;
                cost_high = 9999;
                break;
            default:
                cost_low = -1;
                break;
        }
        return true;
    }

    @Override
    public void enter() {
        reset();
        loadEditions(); //just to be safe since it's preloaded, if somehow edition is null, then reload it
        editionList.clearListeners();
        editionList.clearItems();
        editionList.setItems(editions.toArray(new CardEdition[0]));
        editionList.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                CardEdition E = editionList.getSelected();
                edition = E.getCode();
                editionList.setColor(Color.RED);
                filterResults();
            }
        });
        editionList.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                editionList.showScrollPane();
            }
        });
        editionList.setColor(Color.WHITE);
        filterResults();
        super.enter();
    }


    public void filterResults() {
        Iterable<PaperCard> P = RewardData.getAllCards();
        float totalCost = basePrice * Current.player().goldModifier() * changes.getTownPriceModifier();
        final List<String> colorFilter = new ArrayList<>();
        for (Map.Entry<String, TextraButton> B : colorButtons.entrySet())
            switch (B.getKey()) {
                case "BColorless":
                    if (B.getValue().getColor().equals(Color.RED)) colorFilter.add("Colorless");
                    continue;
                case "BBlack":
                    if (B.getValue().getColor().equals(Color.RED)) colorFilter.add("Black");
                    break;
                case "BBlue":
                    if (B.getValue().getColor().equals(Color.RED)) colorFilter.add("Blue");
                    break;
                case "BGreen":
                    if (B.getValue().getColor().equals(Color.RED)) colorFilter.add("Green");
                    break;
                case "BRed":
                    if (B.getValue().getColor().equals(Color.RED)) colorFilter.add("Red");
                    break;
                case "BWhite":
                    if (B.getValue().getColor().equals(Color.RED)) colorFilter.add("White");
                    break;
            }
        P = StreamSupport.stream(P.spliterator(), false).filter(input -> {
            //L|Basic Land, C|Common, U|Uncommon, R|Rare, M|Mythic Rare, S|Special, N|None
            if (input == null) return false;
            final CardEdition cardEdition = FModel.getMagicDb().getEditions().get(edition);

            if (cardEdition != null && cardEdition.getCardInSet(input.getName()).size() == 0) return false;
            if (colorFilter.size() > 0)
                if (input.getRules().getColor() != ColorSet.fromNames(colorFilter)) return false;
            if (!rarity.isEmpty()) if (!input.getRarity().toString().equals(rarity)) return false;
            if (cost_low > -1) {
                if (!(input.getRules().getManaCost().getCMC() >= cost_low && input.getRules().getManaCost().getCMC() <= cost_high))
                    return false;
            }
            return true;
        }).collect(Collectors.toList());
        //Stream method is very fast, might not be necessary to precache anything.
        if (!edition.isEmpty())
            totalCost *= 4.0f; //Edition select cost multiplier. This is a huge factor, so it's most expensive.
        if (colorFilter.size() > 0)
            totalCost *= Math.min(colorFilter.size() * 2.5f, 6.0f); //Color filter cost multiplier.
        if (!rarity.isEmpty()) { //Rarity cost multiplier.
            switch (rarity) {
                case "C":
                    totalCost *= 1.5f;
                    break;
                case "U":
                    totalCost *= 2.5f;
                    break;
                case "R":
                    totalCost *= 4.0f;
                    break;
                case "M":
                    totalCost *= 5.5f;
                    break;
                default:
                    break;
            }
        }
        if (cost_low > -1) totalCost *= 2.5f; //And CMC cost multiplier.

        cardPool = StreamSupport.stream(P.spliterator(), false).collect(Collectors.toList());
        poolSize.setText(((cardPool.size() > 0 ? "[/][FOREST]" : "[/][RED]")) + cardPool.size() + " possible card" + (cardPool.size() != 1 ? "s" : ""));
        currentPrice = (int) totalCost;
        currentShardPrice = (int) (totalCost * 0.2f); //Intentionally rounding up via the cast to int
        pullUsingGold.setText("[+Pull][+goldcoin] "+ currentPrice);
        pullPackUsingGold.setText("[+Pull][+goldcoin] "+ currentPrice);
        pullUsingShards.setText("[+Pull][+shards]" + currentShardPrice);
        pullUsingGold.setDisabled(!(cardPool.size() > 0) || Current.player().getGold() < totalCost);
        pullPackUsingGold.setDisabled(!(cardPool.size() > 0) || Current.player().getGold() < totalCost);
        pullUsingShards.setDisabled(!(cardPool.size() > 0) || Current.player().getShards() < currentShardPrice);
        editionList.setUserObject(edition);
    }

    public void pullPack(boolean usingShards) {
        paidInShards = usingShards;
        Array<Reward> rewardPack = new Array<>();

        // Separate card pool by rarity and type
        List<PaperCard> commonCards = cardPool.stream()
                .filter(card -> card.getRarity().toString().equals("C"))
                .collect(Collectors.toList());
        List<PaperCard> uncommonCards = cardPool.stream()
                .filter(card -> card.getRarity().toString().equals("U"))
                .collect(Collectors.toList());
        List<PaperCard> rareCards = cardPool.stream()
                .filter(card -> card.getRarity().toString().equals("R"))
                .collect(Collectors.toList());
        List<PaperCard> mythicRareCards = cardPool.stream()
                .filter(card -> card.getRarity().toString().equals("M"))
                .collect(Collectors.toList());
        List<PaperCard> basicLands = cardPool.stream()
                .filter(card -> card.getRules().getType().isLand() && card.isVeryBasicLand())
                .collect(Collectors.toList());

        // Pull 1 basic land
        if (!basicLands.isEmpty()) {
            PaperCard P = basicLands.get(MyRandom.getRandom().nextInt(basicLands.size()));
            Reward reward = createReward(P);
            rewardPack.add(reward);
        }

        // Pull 10 commons
        for (int i = 0; i < 10 && !commonCards.isEmpty(); i++) {
            PaperCard P = commonCards.get(MyRandom.getRandom().nextInt(commonCards.size()));
            Reward reward = createReward(P);
            rewardPack.add(reward);
        }

        // Pull 2 uncommons
        for (int i = 0; i < 2 && !uncommonCards.isEmpty(); i++) {
            PaperCard P = uncommonCards.get(MyRandom.getRandom().nextInt(uncommonCards.size()));
            Reward reward = createReward(P);
            rewardPack.add(reward);
        }

        // Pull 1 uncommon or any
        if (!commonCards.isEmpty()) {
            PaperCard P;
            if (MyRandom.getRandom().nextInt(3) == 0) {
                // 1 in 3 chance of pulling a rare or mythic rare
                if (!mythicRareCards.isEmpty()) {
                    P = mythicRareCards.get(MyRandom.getRandom().nextInt(mythicRareCards.size()));
                } else {
                    P = rareCards.get(MyRandom.getRandom().nextInt(rareCards.size()));
                }
            } else {
                P = uncommonCards.get(MyRandom.getRandom().nextInt(commonCards.size()));
            }
            Reward reward = createReward(P);
            rewardPack.add(reward);
        }

        // Pull 1 rare with a 1/8th chance of being a mythic rare
        if (!rareCards.isEmpty()) {
            // 1/8th chance of pulling a mythic rare
            if (MyRandom.getRandom().nextInt(8) == 0 && !mythicRareCards.isEmpty()) {
                PaperCard P = mythicRareCards.get(MyRandom.getRandom().nextInt(mythicRareCards.size()));
                Reward reward = createReward(P);
                rewardPack.add(reward);
            } else {
                PaperCard P = rareCards.get(MyRandom.getRandom().nextInt(rareCards.size()));
                Reward reward = createReward(P);
                rewardPack.add(reward);
            }
        }

        // Display the rewards using the reward scene
        showRewardScene(rewardPack);
    }

    private Reward createReward(PaperCard P) {
        if (Config.instance().getSettingData().useAllCardVariants) {
            if (!edition.isEmpty()) {
                return new Reward(CardUtil.getCardByNameAndEdition(P.getCardName(), edition));
            } else {
                return new Reward(CardUtil.getCardByName(P.getCardName()));
            }
        } else {
            return new Reward(P);
        }
    }

    private void showRewardScene(Array<Reward> rewards) {
        RewardScene.instance().loadRewards(rewards, RewardScene.Type.Loot, null);
        Forge.switchScene(RewardScene.instance());
    }

    public void pullCard(boolean usingShards) {
        paidInShards = usingShards;
        PaperCard P = cardPool.get(MyRandom.getRandom().nextInt(cardPool.size())); //Don't use the standard RNG.
        currentReward = null;
        if (Config.instance().getSettingData().useAllCardVariants) {
            if (!edition.isEmpty()) {
                currentReward = new Reward(CardUtil.getCardByNameAndEdition(P.getCardName(), edition));
            } else {
                currentReward = new Reward(CardUtil.getCardByName(P.getCardName())); // grab any random variant if no set preference is specified
            }
        } else {
            currentReward = new Reward(P);
        }
        if (rewardActor != null) rewardActor.remove();
        rewardActor = new RewardActor(currentReward, true, null, true);
        rewardActor.flip(); //Make it flip so it draws visual attention, why not.
        rewardActor.setBounds(rewardDummy.getX(), rewardDummy.getY(), rewardDummy.getWidth(), rewardDummy.getHeight());
        stage.addActor(rewardActor);

        acceptReward.setVisible(true);
        declineReward.setVisible(true);
        exitSmith.setDisabled(true);
        disablePullButtons();
    }

    private void acceptSmithing() {
        if (paidInShards) {
            Current.player().takeShards(currentShardPrice);
        } else {
            Current.player().takeGold(currentPrice);
        }

        Current.player().addReward(currentReward);

        clearReward();
        updatePullButtons();
    }

    private void declineSmithing() {
        // Decline the smith reward for 10% of original price
        float priceAdjustment = .10f;
        if (paidInShards) {
            Current.player().takeShards((int)(currentShardPrice * priceAdjustment));
        } else {
            Current.player().takeGold((int)(currentPrice * priceAdjustment));
        }

        clearReward();
        updatePullButtons();
    }

    private void clearReward() {
        if (rewardActor != null) rewardActor.remove();
        currentReward = null;
    }


    private void updatePullButtons() {
        pullUsingGold.setDisabled(Current.player().getGold() < currentPrice);
        pullPackUsingGold.setDisabled(Current.player().getGold() < currentPrice);
        pullUsingShards.setDisabled(Current.player().getShards() < currentShardPrice);

        acceptReward.setVisible(false);
        declineReward.setVisible(false);

        exitSmith.setDisabled(false);
    }

    private void disablePullButtons() {
        pullUsingGold.setDisabled(true);
        pullPackUsingGold.setDisabled(true);
        pullUsingShards.setDisabled(true);
    }
}
