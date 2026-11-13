package tbs.director;

import java.util.List;

/** Снимок того, что игрок делает сейчас. Из него собирается описание для ИИ. */
public final class Situation {
    String name;
    String dimension;
    String biome;
    int y;
    int light;
    boolean underground;
    boolean dark;
    boolean night;
    boolean alone;
    int nearestPlayer; // блоков, -1 если никого
    int afkSeconds;
    int mined;
    int placed;
    float health;
    float maxHealth;
    boolean recentlyDied;
    boolean sleeping;
    String held;
    String chat; // фраза игрока, на которую отвечаем (или null)
    List<String> recentChat = List.of();
    List<String> pastLines = List.of();
    List<String> nearbyTbs = List.of();   // «Лихорадка, 23 блока»
    List<String> tbsEvents = List.of();   // «появилась Лихорадка (40 с назад)»
    java.util.List<String> voices = java.util.List.of("Null"); // кем можно говорить прямо сейчас
    boolean tbsClose;                     // сущность TBS ближе 32 блоков
    int reputation = -1;                  // репутация у сущностей TBS, 0–100
    List<String> pastActions = List.of(); // что режиссёр делал с ним недавно
    boolean forceWorld;                   // админ попросил ощутимое действие
    String worldState = "";
    boolean ru = true;                    // язык игрока: реплики по-русски или по-английски
    List<String> memories = List.of();    // что Null помнит о нём               // как сильно умер мир

    String repTier() {
        return TbsBridge.repTier(reputation);
    }

    String describe() {
        StringBuilder b = new StringBuilder();
        b.append("Игрок: ").append(name).append('\n');
        b.append("Мир: ").append(dimension).append(", биом ").append(biome).append(", высота Y=").append(y).append('\n');
        b.append("Время: ").append(night ? "ночь" : "день").append('\n');
        if (!worldState.isEmpty()) {
            b.append(worldState).append('\n');
        }
        b.append(underground ? "Под землёй, неба не видно. " : "На поверхности. ");
        b.append("Освещение ").append(light).append(" из 15").append(dark ? " (темно)" : "").append('\n');
        if (nearestPlayer < 0) {
            b.append("Один в этом мире, других игроков рядом нет\n");
        } else if (alone) {
            b.append("Один: ближайший игрок далеко, в ").append(nearestPlayer).append(" блоках\n");
        } else {
            b.append("Рядом другой игрок, в ").append(nearestPlayer).append(" блоках\n");
        }
        b.append("Здоровье ").append(Math.round(health)).append('/').append(Math.round(maxHealth)).append('\n');
        if (reputation >= 0) {
            b.append("Репутация у Null и сущностей: ").append(reputation).append("/100 — ").append(switch (repTier()) {
                case "BAD" -> "BAD: Null зол на него, наказывай";
                case "GOOD" -> "GOOD: Null к нему благосклонен — жутко, но без вреда, можешь даже предупредить";
                default -> "NORMAL: Null присматривается к нему";
            }).append('\n');
        }
        if (afkSeconds >= 60) {
            b.append("Стоит на месте уже ").append(afkSeconds).append(" с\n");
        }
        if (mined > 0) {
            b.append("За последние минуты сломал блоков: ").append(mined).append('\n');
        }
        if (placed > 0) {
            b.append("За последние минуты поставил блоков: ").append(placed).append('\n');
        }
        if (held != null && !held.isEmpty()) {
            b.append("В руке: ").append(held).append('\n');
        }
        if (sleeping) {
            b.append("Лёг спать\n");
        }
        if (recentlyDied) {
            b.append("Недавно погиб\n");
        }
        if (!nearbyTbs.isEmpty()) {
            b.append("Рядом сущности The Broken Script: ").append(String.join("; ", nearbyTbs)).append('\n');
        }
        b.append("Голоса, доступные сейчас (speaker): ").append(String.join(", ", voices))
                .append(voices.size() == 1 ? " — других сущностей рядом нет, говори только как Null\n" : "\n");
        if (!tbsEvents.isEmpty()) {
            b.append("Недавние события с ним: ").append(String.join("; ", tbsEvents)).append('\n');
        }
        if (!recentChat.isEmpty()) {
            b.append("Последнее, что он писал в чат: ").append(String.join(" | ", recentChat)).append('\n');
        }
        if (chat != null) {
            b.append("Только что написал в чат: «").append(chat).append("» — ответь на это.\n");
        }
        if (!memories.isEmpty()) {
            b.append("Что ты помнишь о нём (иногда возвращайся к этому): ").append(String.join("; ", memories)).append('\n');
        }
        if (!pastActions.isEmpty()) {
            b.append("Твои последние действия с ним (не повторяй, выбери другое): ").append(String.join(", ", pastActions)).append('\n');
        }
        if (forceWorld) {
            b.append("Сейчас нужно ощутимое действие в мире или событие tbs:, не просто шёпот.\n");
        }
        if (!pastLines.isEmpty()) {
            b.append("Твои прошлые реплики ему (не повторяй): ").append(String.join(" | ", pastLines)).append('\n');
        }
        if (!ru) {
            b.append("Язык игрока: английский. Reply language: English — write \"line\" in natural English.\n");
        }
        return b.toString();
    }
}
