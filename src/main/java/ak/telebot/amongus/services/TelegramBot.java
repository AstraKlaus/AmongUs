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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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
        if (update.hasCallbackQuery()){
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
                Lobby lobby = person.getLobby();
                if (information==null) {
                    switch (callback) {
                        case "Impostor" -> rulesButtons(chatId, "Impostor", 1, 1, 6);
                        case "Tasks" -> rulesButtons(chatId, "Tasks", 1, 1, 9);
                        case "Lobby" -> sendMessage(chatId, "Вы находитесь в комнате: " + lobby.getName());
                        case "Discussion" -> rulesButtons(chatId, "Discussion", 60, 15, 180);
                        case "Cooldown" -> rulesButtons(chatId, "Cooldown", 60, 15, 180);
                        case "Meetings" -> rulesButtons(chatId, "Meetings", 1, 1, 6);
                    }
                }else {
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

        if(update.hasMessage() && update.getMessage().hasText()){
            String text = update.getMessage().getText();
            Message message = update.getMessage();
            long chatId = update.getMessage().getChatId();
            Optional<Person> optionalPerson = personRepository.findById(chatId);
            Person person = null;
            if (optionalPerson.isPresent()){
                person = optionalPerson.get();
            }
            switch (text){
                case "/start":
                    startCommandReceived(chatId, message.getChat().getFirstName());
                    registerUser(message);
                    break;
                case "Начать":
                    break;
                case "Настройки":
                    settingsButtons(chatId);
                    break;
                case "Посмотреть участников":
                    if (person!=null){
                        Optional<List<Person>> optionalPersons = personRepository.findAllByLobby(person.getLobby());
                        StringBuilder stringBuilder = new StringBuilder("В комнате находятся: \n");
                        if (optionalPersons.isPresent()){
                            List<Person> persons = optionalPersons.get();
                            for (Person user : persons) {
                                stringBuilder.append(user.getFirstName()).append("\n");
                            }
                            stringBuilder.append("Всего участников: ").append(persons.size());
                        }
                        sendMessage(chatId, stringBuilder.toString());
                    }else { sendMessage(chatId, "Вас не удалось найти в базе данных"); }
                    break;
                case "Выйти из комнаты":
                    if (person!=null) {
                        person.setLobby(null);
                        personRepository.save(person);
                        sendMessage(chatId, "Вы успешно вышли из комнаты", true);
                    }else { sendMessage(chatId, "Вас не удалось найти в базе данных"); }
                    break;
                default:
                    if (person!=null){
                        if (person.getLobby()==null){
                            startButtons(chatId, message);
                        }else {
                            Optional<List<Person>> optionalPersons = personRepository.findAllByLobby(person.getLobby());
                            if (optionalPersons.isPresent()){
                                List<Person> persons = optionalPersons.get();
                                for (Person user : persons) {
                                    if (user.getChatId() != chatId) sendMessage(user.getChatId(), text);
                                }
                            }
                        }
                    }else { sendMessage(chatId, "Вас не удалось найти в базе данных"); }
            }
        }
    }

    private void rulesButtons(long chatId, String choose , int start, int step, int count){
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(String.valueOf(chatId));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();

        for (int i = start; i <= count; i += step*3){
            InlineKeyboardButton button1 = new InlineKeyboardButton(String.valueOf(i));
            button1.setCallbackData(choose + "@" + i);

            InlineKeyboardButton button2 = new InlineKeyboardButton(String.valueOf(i+step));
            button2.setCallbackData(choose + "@" + (i+step));

            InlineKeyboardButton button3 = new InlineKeyboardButton(String.valueOf(i+step+step));
            button3.setCallbackData(choose + "@" + (i+step+step));

            rowsInLine.add(List.of(button1,button2,button3));
        }

        markup.setKeyboard(rowsInLine);
        sendMessage.setReplyMarkup(markup);

        sendMessage.setReplyMarkup(markup);
        sendMessage.setText("Какое значение установить?");
        try {
            execute(sendMessage);
        }catch (TelegramApiException e) {
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
        }catch (TelegramApiException e) {
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
        }catch (TelegramApiException e) {
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
        if (optionalLobby.isPresent() && optionalLobby.get().getOwner().getChatId() == chatId) {
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
        sendMessage.setText("Вы находитесь в комнате: " + lobbyName);
        try {
            execute(sendMessage);
        }catch (TelegramApiException e) {
            log.error("Кнопки комнаты не отправилось " + e.getMessage());
        }
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
        if (personRepository.findById(msg.getChatId()).isEmpty()){
            Long chatId = msg.getChatId();
            Chat chat = msg.getChat();

            Person person = new Person();

            person.setChatId(chatId);
            person.setFirstName(chat.getFirstName());
            person.setRegisteredAt(new Timestamp(System.currentTimeMillis()));

            personRepository.save(person);
        }
    }

    private void startCommandReceived(long chatId, String name){
        String answer = "Добро пожаловать в амогус, " + name;
        sendMessage(chatId, answer);
    }

    private void sendMessage(long chatId, String text){
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        try {
            execute(message);
        }catch (TelegramApiException e) {
            log.error("Сообщение не отправилось " + e.getMessage());
        }
    }

    private void sendMessage(long chatId, String text, boolean deleteKeyboard){
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        if (deleteKeyboard) message.setReplyMarkup(new ReplyKeyboardRemove(true));
        try {
            execute(message);
        }catch (TelegramApiException e) {
            log.error("Сообщение не отправилось " + e.getMessage());
        }
    }

    private void sendEditMessage(long chatId, String text, long messageId){
        EditMessageText message = new EditMessageText();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setMessageId((int) messageId);

        try {
            execute(message);
        }catch (TelegramApiException e) {
            log.error("Не получилось отредактировать сообщение " + e.getMessage());
        }
    }
}
