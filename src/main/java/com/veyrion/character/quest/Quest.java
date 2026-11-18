package com.veyrion.character.quest;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * An immutable quest definition loaded from {@code quests/*.yml}.
 *
 * @param cooldownSeconds minimum time between completions (repeatable quests)
 * @param requiredRank minimum Adventure Rank name, or null
 * @param vyrReward Veyra (VYR) paid through Vault
 */
public record Quest(
        String id,
        String name,
        String description,
        QuestType type,
        long cooldownSeconds,
        @Nullable String requiredRank,
        List<String> prerequisites,
        long expReward,
        double vyrReward,
        List<QuestObjective> objectives) {

    public Quest {
        prerequisites = List.copyOf(prerequisites);
        objectives = List.copyOf(objectives);
    }
}
