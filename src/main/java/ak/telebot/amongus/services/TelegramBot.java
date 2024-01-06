package ak.telebot.amongus.services;

import ak.telebot.amongus.configs.BotConfig;
import ak.telebot.amongus.models.Lobby;
import ak.telebot.amongus.models.Person;
import ak.telebot.amongus.repositories.LobbyRepository;
import ak.telebot.amongus.repositories.PersonRepository;
import ak.telebot.amongus.repositories.TaskRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@Transactional
public class TelegramBot extends TelegramLongPollingBot {

    private final PersonRepository personRepository;
    private final LobbyRepository lobbyRepository;
    private final TaskRepository taskRepository;
    private final BotConfig botConfig;

    @Autowired
    public TelegramBot(PersonRepository personRepository,
                       LobbyRepository lobbyRepository,
                       TaskRepository taskRepository,
                       BotConfig botConfig) {
        this.personRepository = personRepository;
        this.lobbyRepository = lobbyRepository;
        this.taskRepository = taskRepository;
        this.botConfig = botConfig;
    }

    @Override
    public String getBotUsername() {
        return botConfig.getName();
    }

    @Override
    public String getBotToken() {
        return botConfig.getToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasCallbackQuery()) {
            long chatId = update.getCallbackQuery().getMessage().getChatId();
            Optional<Person> optionalPerson = personRepository.findById(chatId);
            if (optionalPerson.isPresent()) {
                Person person = optionalPerson.get();

                long messageId = update.getCallbackQuery().getMessage().getMessageId();
                String callback = update.getCallbackQuery().getData();
                String information = null;
                if (callback.contains("@")) information = update.getCallbackQuery().getData().split("@")[1];
                if (callback.contains("CREATE_LOBBY")) {
                    if (!lobbyRepository.existsByName(information)) {
                        Lobby newLobby = new Lobby();
                        newLobby.setName(information);

                        lobbyRepository.save(newLobby);

                        person.setLobby(newLobby);
                        personRepository.save(person);

                        sendEditMessage(chatId, "Вы выбрали создать комнату", messageId);
                        lobbyButtons(chatId, information);

                    } else sendMessage(chatId, "Комната с таким именем уже существует");
                    return;
                }
                if (callback.contains("JOIN_LOBBY")) {
                    Optional<Lobby> optionalLobby = lobbyRepository.findByName(information);

                    if (optionalLobby.isPresent()) {
                        Lobby lobby = optionalLobby.get();
                        person.setLobby(lobby);
                        personRepository.save(person);

                        sendEditMessage(chatId, "Вы выбрали присоединиться к комнате", messageId);
                        lobbyButtons(chatId, information);
                    } else sendMessage(chatId, "Не удалось найти комнату");
                    return;
                }
                if (callback.contains("VOTE")) {
                    person.setVotedId(Long.valueOf(information));
                    personRepository.save(person);
                    sendEditMessage(chatId, "Вы выбрали: " + personRepository.findById(Long.valueOf(information)).get().getFirstName(), messageId);
                    var optionalPersons = personRepository.findAllByLobby(person.getLobby());
                    if (optionalPersons.isPresent()) {
                        var alivePersons = optionalPersons.get().stream().filter(Person::isAlive).toList();
                        if (alivePersons.stream().allMatch(p -> p.getVotedId() != 0)) {
                            var kickId = findMostFrequentNumbers(alivePersons.stream().map(Person::getVotedId).toList());
                            if (kickId.contains(0L) || kickId.size() > 1) { sendMessageToEverybody("Вы никого не выгнали", person); return;}
                            Optional<Person> optionalKickingPerson = personRepository.findById(kickId.get(0));
                            if (optionalKickingPerson.isPresent()){
                                Person kickingPerson = optionalKickingPerson.get();
                                kickingPerson.setAlive(false);
                                kickingPerson.setDiscussions(0);
                                kickingPerson.setImpostor(false);
                                personRepository.save(kickingPerson);
                                sendMessageToEverybody(kickingPerson.getFirstName() + " сброшен в говняную яму", kickingPerson);
                            }
                        }
                    }
                    return;
                }
                Lobby lobby = person.getLobby();
                if (information == null) {
                    switch (callback) {
                        case "Impostor" -> rulesButtons(chatId, "Impostor", 1, 1, 6);
                        case "Tasks" -> rulesButtons(chatId, "Tasks", 1, 1, 9);
                        case "Lobby" -> sendMessage(chatId, "Вы находитесь в комнате: " + lobby.getName());
                        case "Discussion" -> rulesButtons(chatId, "Discussion", 60, 15, 180);
                        case "Cooldown" -> rulesButtons(chatId, "Cooldown", 60, 15, 180);
                        case "Meetings" -> rulesButtons(chatId, "Meetings", 1, 1, 6);
                        case "TurnOff" -> turnOff(chatId, lobby, messageId);
                        case "Nuclear" -> nuclearStarts(messageId, chatId, lobby);
                        case "First" -> checkNuclear(lobby, false, messageId);
                        case "Second" -> checkNuclear(lobby, true, messageId);
                        case "TurnOn" -> turnOn(chatId, lobby, messageId);
                    }
                    lobbyRepository.save(lobby);
                } else {
                    if (callback.contains("Impostor")) {
                        lobby.setNumberOfImpostors(Integer.parseInt(information));
                    } else if (callback.contains("Tasks")) {
                        lobby.setNumberOfTasks(Integer.parseInt(information));
                    } else if (callback.contains("Discussion")) {
                        lobby.setDiscussionTime(Integer.parseInt(information));
                    } else if (callback.contains("Cooldown")) {
                        lobby.setKillCooldown(Integer.parseInt(information));
                    } else if (callback.contains("Meetings")) {
                        lobby.setNumberOfEmergencyMeetings(Integer.parseInt(information));
                    }

                    lobbyRepository.save(lobby);

                    sendEditMessage(chatId, "Значение успешно установлено на " + information, messageId);
                }
            }
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText();
            Message message = update.getMessage();
            long chatId = update.getMessage().getChatId();
            Optional<Person> optionalPerson = personRepository.findById(chatId);
            Person person = null;
            Lobby lobby = null;
            if (optionalPerson.isPresent()) {
                person = optionalPerson.get();
                lobby = person.getLobby();
            }
            switch (text) {
                case "/start":
                    startCommandReceived(chatId, message.getChat().getFirstName());
                    registerUser(message);
                    break;
                case "Начать":
                    if (lobby != null) {
                        for (Person user : lobby.getPersons()) {
                            user.setDiscussions(lobby.getNumberOfEmergencyMeetings());
                            personRepository.save(user);
                        }

                        playButtons(lobby);
                        lobby.setStartedAt(getTime());
                        lobby.setAlive(lobby.getPersons().size());
                        lobby.setDiscussionAt(getTime());
                        lobby.setTasksDone(0);
                        lobby.setTurnOn(true);
                        lobby.setSecondIsActive(false);
                        lobby.setFirstIsActive(false);
                        lobby.setEndedAt(null);
                        lobbyRepository.save(lobby);
                    }
                    break;
                case "Настройки":
                    settingsButtons(chatId);
                    break;
                case "Посмотреть участников":
                    if (person != null) {
                        Optional<List<Person>> optionalPersons = personRepository.findAllByLobby(person.getLobby());
                        StringBuilder stringBuilder = new StringBuilder("В комнате находятся: \n");
                        if (optionalPersons.isPresent()) {
                            List<Person> persons = optionalPersons.get();
                            for (Person user : persons) {
                                stringBuilder.append(user.getFirstName()).append("\n");
                            }
                            stringBuilder.append("Всего участников: ").append(persons.size());
                        }
                        sendMessage(chatId, stringBuilder.toString());
                    } else {
                        sendMessage(chatId, "❌Вас не удалось найти в базе данных❌");
                    }
                    break;
                case "Выйти из комнаты":
                    if (person != null) {
                        person.setLobby(null);
                        personRepository.save(person);
                        sendMessage(chatId, "Вы успешно вышли из комнаты", true);
                    } else {
                        sendMessage(chatId, "❌Вас не удалось найти в базе данных❌");
                    }
                    break;
                case "Собрание":
                    if (person != null) {
                        if (person.getDiscussions() == 0) {
                            sendMessage(chatId, "⚠️Вы уже вызывали собрание");
                        } else if (getDateDiff(lobby.getDiscussionAt(), getTime(), TimeUnit.SECONDS) < 3) {
                            sendMessage(chatId, "До вызова следующего собрания осталось: " + (360 - getDateDiff(lobby.getDiscussionAt(), getTime(), TimeUnit.SECONDS)));
                        } else {
                            person.setDiscussions(person.getDiscussions() - 1);
                            personRepository.save(person);
                            sendMessageToEverybody("\uD83D\uDD34НАЧАЛОСЬ СОБРАНИЕ\uD83D\uDD34", person);
                            meetingButtons(lobby.getPersons());
                        }
                    }
                    break;
                case "Труп":
                    if (person != null) {
                        sendMessageToEverybody("\uD83D\uDD34НАЙДЕН ТРУП\uD83D\uDD34", person);
                        meetingButtons(lobby.getPersons());
                    }
                    break;
                case "Меня убили":
                    if (lobby != null) {
                        if (!person.isImpostor()) {
                            lobby.setAlive(lobby.getAlive() - 1);
                            lobbyRepository.save(lobby);
                            if (lobby.getAlive() == lobby.getNumberOfImpostors()) {
                                endGame(lobby, lobby.getName(), "\uD83D\uDC79Большая часть мирных убито, победа импостеров!\uD83D\uDC79");
                            }
                        } else {
                            impostorButtons(chatId);
                        }
                    }
                    break;
                default:
                    if (person != null) {
                        if (person.getLobby() == null) {
                            startButtons(chatId, message);
                        } else {
                            sendMessageToEverybody(text, person);
                        }
                    } else {
                        sendMessage(chatId, "Вас не удалось найти в базе данных");
                    }
            }
        }
    }

    private void meetingButtons(List<Person> persons) {
        SendMessage sendMessage = new SendMessage();

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();

        List<Person> alivePersons = persons.stream().filter(Person::isAlive).toList();

        for (int i = 0; i <= alivePersons.size(); i += 3) {
            List<InlineKeyboardButton> inline = new ArrayList<>();

            String name = alivePersons.get(i).getChatId().toString();
            InlineKeyboardButton button1 = new InlineKeyboardButton(alivePersons.get(i).getFirstName());
            button1.setCallbackData("VOTE@" + name);

            inline.add(button1);

            if (i + 1 < alivePersons.size()) {
                name = alivePersons.get(i + 1).getChatId().toString();
                InlineKeyboardButton button2 = new InlineKeyboardButton(alivePersons.get(i+1).getFirstName());
                button2.setCallbackData("VOTE@" + name);
                inline.add(button2);
            }
            if (i + 2 < alivePersons.size()) {
                name = alivePersons.get(i + 2).getChatId().toString();
                InlineKeyboardButton button3 = new InlineKeyboardButton(alivePersons.get(i+2).getFirstName());
                button3.setCallbackData("VOTE@" + name);
                inline.add(button3);
            }
            rowsInLine.add(inline);
        }
        var skipButton = new InlineKeyboardButton("Пропустить");
        skipButton.setCallbackData("VOTE@0");
        rowsInLine.add(Collections.singletonList(skipButton));

        markup.setKeyboard(rowsInLine);
        sendMessage.setReplyMarkup(markup);

        sendMessage.setReplyMarkup(markup);
        sendMessage.setText("За кого голосуешь?");
        for (Person person : alivePersons) {
            person.setVotedId(0L);
            personRepository.save(person);
            sendMessage.setChatId(String.valueOf(person.getChatId()));
            try {
                execute(sendMessage);
            } catch (TelegramApiException e) {
                log.error("Кнопки комнаты не отправилось " + e.getMessage());
            }
        }
    }

    private void sendMessageToEverybody(String text, Person person) {
        Optional<List<Person>> optionalPersons = personRepository.findAllByLobby(person.getLobby());
        if (optionalPersons.isPresent()) {
            List<Person> persons = optionalPersons.get();
            for (Person user : persons) {sendMessage(user.getChatId(), text);}
        }
    }

    private void turnOn(long chatId, Lobby lobby, long messageId) {
        lobby.setTurnOn(true);
        for (Person person : lobby.getPersons()) {
            sendMessage(person.getChatId(), "СВЕТ ВКЛЮЧЕН");
        }
        sendEditMessage(chatId, "Вы включили свет", messageId);
    }

    private void endGame(Lobby lobby, String lobbyName, String winner) {
        lobby.setEndedAt(getTime());
        lobbyRepository.save(lobby);
        System.out.println(lobby.getStartedAt());
        System.out.println(lobby.getEndedAt());
        long minutes = getDateDiff(lobby.getStartedAt(), lobby.getEndedAt(), TimeUnit.MINUTES);
        for (Person player : lobby.getPersons()) {
            player.setImpostor(false);
            sendMessage(player.getChatId(), "Игра длилась: " + (minutes) + "мин.\n" + winner);
            lobbyButtons(player.getChatId(), lobbyName);

            personRepository.save(player);
        }
    }

    private void checkNuclear(Lobby lobby, boolean isSecond, long messageId) {
        if (getDateDiff(lobby.getNuclearAt(), getTime(), TimeUnit.SECONDS) <= 180) {
            if (!isSecond) lobby.setFirstIsActive(false);
            if (isSecond) lobby.setSecondIsActive(false);
            lobbyRepository.save(lobby);
        } else {
            for (Person person : lobby.getPersons()) {
                sendEditMessage(person.getChatId(), "Вы не успели", messageId);
            }
            endGame(lobby, lobby.getName(), "Реактор взорван, импостеры победили!");
        }

        if (!lobby.isFirstIsActive() && !lobby.isSecondIsActive()) {
            System.out.println(getDateDiff(getTime(), lobby.getNuclearAt(), TimeUnit.SECONDS));
            for (Person person : lobby.getPersons()) {
                sendEditMessage(person.getChatId(), "Реактор выключен", messageId);
            }
        }
    }

    private void nuclearStarts(long messageId, long chatId, Lobby lobby) {
        if (getDateDiff(lobby.getNuclearAt(), getTime(), TimeUnit.SECONDS) > 360) {

            DeleteMessage deleteMessage = new DeleteMessage();
            deleteMessage.setChatId(String.valueOf(chatId));
            deleteMessage.setMessageId((int) messageId);

            lobby.setFirstIsActive(true);
            lobby.setSecondIsActive(true);
            lobby.setNuclearAt(getTime());
            lobbyRepository.save(lobby);
            System.out.println(lobby.getNuclearAt());
            SendMessage sendMessage = new SendMessage();

            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
            List<InlineKeyboardButton> rowInLine1 = new ArrayList<>();

            InlineKeyboardButton first = new InlineKeyboardButton();
            InlineKeyboardButton second = new InlineKeyboardButton();

            first.setText("1 ВЫКЛ.");
            first.setCallbackData("First");

            second.setText("2 ВЫК.");
            second.setCallbackData("Second");

            rowInLine1.add(first);
            rowInLine1.add(second);

            rowsInLine.add(rowInLine1);

            markup.setKeyboard(rowsInLine);
            sendMessage.setReplyMarkup(markup);

            sendMessage.setReplyMarkup(markup);
            sendMessage.setText("Какой ты выключил?");

            for (Person person : lobby.getPersons()) {
                sendMessage.setChatId(String.valueOf(person.getChatId()));
                try {
                    execute(deleteMessage);
                    execute(sendMessage);
                } catch (TelegramApiException e) {
                    log.error("Кнопки комнаты не отправилось " + e.getMessage());
                }
            }
        } else {
            sendEditMessage(chatId, "До вызова реактора осталось: " + (360 - getDateDiff(lobby.getNuclearAt(), getTime(), TimeUnit.SECONDS)), messageId);
        }
    }

    public static Timestamp getTime() {
        return new Timestamp(System.currentTimeMillis());
    }

    private void turnOff(long chatId, Lobby lobby, long messageId) {
        lobby.setTurnOn(false);
        sendEditMessage(chatId, "Вы выполнили задание", messageId);
        SendMessage sendMessage = new SendMessage();

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<InlineKeyboardButton> rowInLine1 = new ArrayList<>();

        InlineKeyboardButton TurnOn = new InlineKeyboardButton();

        TurnOn.setText("Я ВКЛЮЧИЛ");
        TurnOn.setCallbackData("TurnOn");

        rowInLine1.add(TurnOn);

        rowsInLine.add(rowInLine1);

        markup.setKeyboard(rowsInLine);
        sendMessage.setReplyMarkup(markup);

        sendMessage.setReplyMarkup(markup);
        sendMessage.setText("ВСЕМ ВЫКЛЮЧИТЬ СВЕТ!\nВключил рубильник?");

        for (Person person : lobby.getPersons()) {
            sendMessage.setChatId(String.valueOf(person.getChatId()));
            try {
                execute(sendMessage);
            } catch (TelegramApiException e) {
                log.error("Кнопки комнаты не отправилось " + e.getMessage());
            }
        }
    }

    private void impostorButtons(long chatId) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(String.valueOf(chatId));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<InlineKeyboardButton> rowInLine1 = new ArrayList<>();
        List<InlineKeyboardButton> rowInLine2 = new ArrayList<>();
        List<InlineKeyboardButton> rowInLine3 = new ArrayList<>();

        InlineKeyboardButton turnOff = new InlineKeyboardButton();
        InlineKeyboardButton nuclear = new InlineKeyboardButton();
        InlineKeyboardButton kill = new InlineKeyboardButton();

        turnOff.setText("Выключить свет");
        turnOff.setCallbackData("TurnOff");

        nuclear.setText("Реактор");
        nuclear.setCallbackData("Nuclear");

        kill.setText("Я убил");
        kill.setCallbackData("Kill");

        rowInLine1.add(turnOff);
        rowInLine2.add(nuclear);
        rowInLine3.add(kill);

        rowsInLine.add(rowInLine1);
        rowsInLine.add(rowInLine2);
        rowsInLine.add(rowInLine3);

        markup.setKeyboard(rowsInLine);
        sendMessage.setReplyMarkup(markup);

        sendMessage.setReplyMarkup(markup);
        sendMessage.setText("Что хотите сделать?");
        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            log.error("Кнопки комнаты не отправилось " + e.getMessage());
        }
    }

    private void playButtons(Lobby lobby) {
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        List<KeyboardRow> rows = new ArrayList<>();
        KeyboardRow rowOne = new KeyboardRow();
        KeyboardRow rowTwo = new KeyboardRow();

        KeyboardButton readyButton = new KeyboardButton("Задания");
        KeyboardButton settingsButton = new KeyboardButton("Труп");

        rowOne.add(readyButton);
        rowOne.add(settingsButton);

        rows.add(rowOne);

        KeyboardButton personButton = new KeyboardButton("Собрание");
        KeyboardButton exitButton = new KeyboardButton("Меня убили");

        rowTwo.add(personButton);
        rowTwo.add(exitButton);

        rows.add(rowTwo);

        markup.setKeyboard(rows);

        SendMessage sendMessage = new SendMessage();

        List<Person> crewmates = lobby.getPersons();
        Collections.shuffle(crewmates);
        List<Person> impostors = new ArrayList<>();
        for (int i = 0; i < lobby.getNumberOfImpostors(); i++) {
            Person impostor = crewmates.remove(0);
            impostors.add(impostor);
            impostor.setImpostor(true);

            personRepository.save(impostor);
        }

        for (Person person : impostors) {
            sendMessage.setChatId(String.valueOf(person.getChatId()));
            sendMessage.setReplyMarkup(markup);
            sendMessage.setText("Игра началась, Вы импостер (•̪●)");
            try {
                execute(sendMessage);
            } catch (TelegramApiException e) {
                log.error("Кнопки комнаты не отправилось " + e.getMessage());
            }
        }

        for (Person person : crewmates) {
            person.setImpostor(false);
            sendMessage.setChatId(String.valueOf(person.getChatId()));
            sendMessage.setReplyMarkup(markup);
            sendMessage.setText("Игра началась, Вы мирный");
            try {
                execute(sendMessage);
            } catch (TelegramApiException e) {
                log.error("Кнопки комнаты не отправилось " + e.getMessage());
            }
        }

        lobby.setStartedAt(getTime());
        lobby.setAlive(crewmates.size());
        lobby.setTasksDone(0);

        lobbyRepository.save(lobby);
    }

    private void rulesButtons(long chatId, String choose, int start, int step, int count) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(String.valueOf(chatId));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();

        for (int i = start; i <= count; i += step * 3) {
            InlineKeyboardButton button1 = new InlineKeyboardButton(String.valueOf(i));
            button1.setCallbackData(choose + "@" + i);

            InlineKeyboardButton button2 = new InlineKeyboardButton(String.valueOf(i + step));
            button2.setCallbackData(choose + "@" + (i + step));

            InlineKeyboardButton button3 = new InlineKeyboardButton(String.valueOf(i + step + step));
            button3.setCallbackData(choose + "@" + (i + step + step));

            rowsInLine.add(List.of(button1, button2, button3));
        }

        markup.setKeyboard(rowsInLine);
        sendMessage.setReplyMarkup(markup);

        sendMessage.setReplyMarkup(markup);
        sendMessage.setText("Какое значение установить?");
        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            log.error("Кнопки комнаты не отправилось " + e.getMessage());
        }
    }

    private void settingsButtons(long chatId) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(String.valueOf(chatId));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<InlineKeyboardButton> rowInLine1 = new ArrayList<>();
        List<InlineKeyboardButton> rowInLine2 = new ArrayList<>();
        List<InlineKeyboardButton> rowInLine3 = new ArrayList<>();

        InlineKeyboardButton impostors = new InlineKeyboardButton();
        InlineKeyboardButton tasks = new InlineKeyboardButton();
        InlineKeyboardButton meetings = new InlineKeyboardButton();
        InlineKeyboardButton cooldown = new InlineKeyboardButton();
        InlineKeyboardButton discussion = new InlineKeyboardButton();
        InlineKeyboardButton lobby = new InlineKeyboardButton();

        impostors.setText("Импосторы");
        impostors.setCallbackData("Impostor");

        tasks.setText("Задания");
        tasks.setCallbackData("Tasks");

        rowInLine1.add(impostors);
        rowInLine1.add(tasks);

        meetings.setText("Собрания");
        meetings.setCallbackData("Meetings");

        cooldown.setText("Убийства");
        cooldown.setCallbackData("Cooldown");

        rowInLine2.add(meetings);
        rowInLine2.add(cooldown);

        discussion.setText("Обсуждение");
        discussion.setCallbackData("Discussion");

        lobby.setText("Комната");
        lobby.setCallbackData("Lobby");

        rowInLine3.add(discussion);
        rowInLine3.add(lobby);

        rowsInLine.add(rowInLine1);
        rowsInLine.add(rowInLine2);
        rowsInLine.add(rowInLine3);

        markup.setKeyboard(rowsInLine);
        sendMessage.setReplyMarkup(markup);

        sendMessage.setReplyMarkup(markup);
        sendMessage.setText("Что хотите настроить?");
        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            log.error("Кнопки комнаты не отправилось " + e.getMessage());
        }
    }

    private void startButtons(long chatId, Message message) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(String.valueOf(chatId));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<InlineKeyboardButton> rowInLine = new ArrayList<>();

        InlineKeyboardButton createRoom = new InlineKeyboardButton();
        InlineKeyboardButton joinRoom = new InlineKeyboardButton();

        createRoom.setText("Создать");
        createRoom.setCallbackData("CREATE_LOBBY@" + message.getText());

        joinRoom.setText("Присоединиться");
        joinRoom.setCallbackData("JOIN_LOBBY@" + message.getText());

        rowInLine.add(createRoom);
        rowInLine.add(joinRoom);

        rowsInLine.add(rowInLine);

        markup.setKeyboard(rowsInLine);
        sendMessage.setReplyMarkup(markup);

        sendMessage.setReplyMarkup(markup);
        sendMessage.setText("Создать комнату или присоединитсья к существующей?");
        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            log.error("Кнопки комнаты не отправилось " + e.getMessage());
        }
    }

    private void lobbyButtons(long chatId, String lobbyName) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(String.valueOf(chatId));

        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        List<KeyboardRow> rows = new ArrayList<>();
        KeyboardRow rowOne = new KeyboardRow();
        KeyboardRow rowTwo = new KeyboardRow();

        Optional<Lobby> optionalLobby = lobbyRepository.findByName(lobbyName);
        if (optionalLobby.isPresent() && optionalLobby.get().getOwner().getChatId().intValue() == (int) chatId) {
            KeyboardButton readyButton = new KeyboardButton("Начать");
            KeyboardButton settingsButton = new KeyboardButton("Настройки");

            rowOne.add(readyButton);
            rowOne.add(settingsButton);

            rows.add(rowOne);
        }

        KeyboardButton personButton = new KeyboardButton("Посмотреть участников");
        KeyboardButton exitButton = new KeyboardButton("Выйти из комнаты");

        rowTwo.add(personButton);
        rowTwo.add(exitButton);

        rows.add(rowTwo);

        markup.setKeyboard(rows);
        sendMessage.setReplyMarkup(markup);
        if (lobbyName.equals("Игра окончена, победа импостеров!")) {
            sendMessage.setText(lobbyName);
        } else {
            sendMessage.setText("Вы находитесь в комнате: " + lobbyName);
        }
        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            log.error("Кнопки комнаты не отправилось " + e.getMessage());
        }
    }

    private static List<Long> findMostFrequentNumbers(List<Long> array) {
        HashMap<Long, Integer> numberCountMap = new HashMap<>();

        for (long number : array) {
            if (numberCountMap.containsKey(number)) {
                numberCountMap.put(number, numberCountMap.get(number) + 1);
            } else {
                numberCountMap.put(number, 1);
            }
        }

        List<Long> mostFrequentNumbers = new ArrayList<>();
        int maxCount = 0;

        for (HashMap.Entry<Long, Integer> entry : numberCountMap.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                mostFrequentNumbers.clear();
                mostFrequentNumbers.add(entry.getKey());
            } else if (entry.getValue() == maxCount) {
                mostFrequentNumbers.add(entry.getKey());
            }
        }

        return mostFrequentNumbers;
    }

    private void taskDone(Message message) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<InlineKeyboardButton> rowInLine = new ArrayList<>();

        InlineKeyboardButton createRoom = new InlineKeyboardButton();

        createRoom.setText("Выполнил задание");
        createRoom.setCallbackData("TASK_DONE");

        rowInLine.add(createRoom);

        rowsInLine.add(rowInLine);

        markup.setKeyboard(rowsInLine);
        message.setReplyMarkup(markup);
    }

    private void registerUser(Message msg) {
        if (personRepository.findById(msg.getChatId()).isEmpty()) {
            Long chatId = msg.getChatId();
            Chat chat = msg.getChat();

            Person person = new Person();

            person.setChatId(chatId);
            person.setFirstName(chat.getFirstName());
            person.setRegisteredAt(getTime());

            personRepository.save(person);
        }
    }

    private void startCommandReceived(long chatId, String name) {
        String answer = "Добро пожаловать в амогус, " + name;
        sendMessage(chatId, answer);
    }

    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Сообщение не отправилось " + e.getMessage());
        }
    }

    private void sendMessage(long chatId, String text, boolean deleteKeyboard) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        if (deleteKeyboard) message.setReplyMarkup(new ReplyKeyboardRemove(true));
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Сообщение не отправилось " + e.getMessage());
        }
    }

    private void sendEditMessage(long chatId, String text, long messageId) {
        EditMessageText message = new EditMessageText();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setMessageId((int) messageId);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Не получилось отредактировать сообщение " + e.getMessage());
        }
    }

    public static long getDateDiff(Timestamp oldTs, Timestamp newTs, TimeUnit timeUnit) {
        long diffInMS = newTs.getTime() - oldTs.getTime();
        return timeUnit.convert(diffInMS, TimeUnit.MILLISECONDS);
    }
}
