package org.nnstu5.database;

//import org.apache.log4j.Logger;
import org.nnstu5.ChatRules;
import org.nnstu5.container.Conversation;
import org.nnstu5.container.CurrentUser;
import org.nnstu5.container.Message;
import org.nnstu5.container.User;
import org.nnstu5.database.handler.ConversationHandler;
import org.nnstu5.database.handler.FriendsHandler;
import org.nnstu5.database.handler.MessageHandler;
import org.nnstu5.database.handler.UserHandler;
import org.nnstu5.database.holder.ArgLine;

import java.sql.SQLException;
import java.util.List;

/**
 * @author Trubkin Nikita
 *         <p>
 *         ChatDatabase организует взаимодействие слоя баз данных с внешним миром посредством программных методов.
 *         Как правило, метод выполняет одно простое действие и, если потребуется, возвращает результат или коллекцию результатов
 *         Если метод использует несколько запросов, используются транзакции
 *         В начале метода обязательна проверка корректности аргументов
 *         Допускается работа с несколькими бд, используя, например, DatabaseController
 *         ChatDatabase реализует singleton паттерн с ленивой инициализацией.
 *         БД открывается DatabaseController при создании
 *         Необходимо закрыть при завершении работы методом close(), который закроет все бд соединения
 *         исключить механики
 */
public class ChatDatabase implements AutoCloseable {

    private static final String DEFAULT_DB_URL = "jdbc:sqlite:src/main/resources/SQL/AChatDatabase.db";
    private static final String ILL_ARGS_MSG = "Some args are incorrect";
    private static final String SQL_ROLLBACK_EXC_MSG = "Rollback error SQL";
    //private static final Logger log = Logger.getLogger(ChatDatabase.class);

    private DatabaseController dbController;     // контроллер бд sqlite

    private UserHandler userHandler;
    private FriendsHandler friendsHandler;
    private ConversationHandler conversHandler;
    private MessageHandler msgHandler;
    private ConvertManager convertManager = new ConvertManager();

    /**
     * Приватный конструктор, поскольку ChatDatabase реализует singleton паттерн
     */
    private ChatDatabase() throws SQLException, ClassNotFoundException {
        try {
            dbController = new DatabaseController(DEFAULT_DB_URL);
            userHandler = new UserHandler(dbController);
            friendsHandler = new FriendsHandler(dbController);
            conversHandler = new ConversationHandler(dbController);
            msgHandler = new MessageHandler(dbController);
            //log.info("ChatDatabase instantiated.");
            System.out.println("ChatDatabase instantiated.");
        } catch (SQLException | ClassNotFoundException exc) {
            // Если мы здесь, значит база данных не инициалицирована.
            // Поскольку этот конструктор используется в on-demand holder idiom singleton, его вызовет LazyHolder.
            // В силу своей статичности, LazyHolder не сможет обработать/перебросить исключение
            // Поэтому исключение игнорируется, логируется.
            // Впоследствии при попытке вызвать getInstance(), метод бросит IllegalStateException
            //log.error("Cannot create DatabaseController ", exc);
            System.out.println("Cannot create DatabaseController " + exc);
            try {
                dbController.close();
            }
            catch (SQLException closeExc) {
                System.out.println("Cannot close after initialization fail");
            }
            throw exc;
        }
    }

    private static ChatDatabase ConstructChatDatabase() {
        try{
            return new ChatDatabase();
        }
        catch (Exception exc) {
            return null;
        }
    }

    /**
     * Класс, помогающий реализовать on-demand holder idiom singleton
     */
    private static class LazyHolder {
        private static final ChatDatabase INSTANCE = ConstructChatDatabase();
    }

    /**
     * Ленивый потокобезопасный singleton, основаный на on-demand holder idiom
     *
     * @return instance
     */
    public static ChatDatabase getInstance() {
        if (LazyHolder.INSTANCE == null) {
            throw new IllegalStateException("Chat database initialization failed");
        } else {
            return LazyHolder.INSTANCE;
        }
    }

    /**
     * Завершает работу с базой данных, закрывает все соединения
     *
     * @throws SQLException
     */
    @Override
    public void close() throws SQLException {
        dbController.close();
        //log.info("ChatDatabase closed.");
        System.out.println("ChatDatabase closed.");
    }

    /**
     * Регистрирует нового пользователя в бд
     *
     * @param user контейнер CurrentUser, который содержит регистрационные данные нового пользователя
     * @throws SQLException
     */
    public void registerUser(CurrentUser user) throws SQLException {
        // проверка аргументов
        if (!ChatRules.isValidUserNickname(user.getNickname())
                || !ChatRules.isValidUserEmail(user.getEmail())
                || !ChatRules.isValidUserPassHash(user.getPassHash())
                || userHandler.findUser(user.getEmail()) != -1) {
            throw new IllegalArgumentException(ILL_ARGS_MSG);
        }

        // вставка нового пользователя с возратом его id
        userHandler.insertUser(user.getNickname(), user.getEmail(), user.getPassHash());
        dbController.commitTransaction();
    }

    /**
     * Проверяет, существует ли пользователь с таким email и passHash в бд
     *
     * @param user контейнер CurrentUser с авторизационной информацией
     * @return контейнер User с информацией об авторизированном пользователе
     * @throws SQLException
     */
    public User authorizeUser(CurrentUser user) throws SQLException {
        // проверка аргументов
        if (!ChatRules.isValidUserEmail(user.getEmail()) || !ChatRules.isValidUserPassHash(user.getPassHash())) {
            throw new IllegalArgumentException(ILL_ARGS_MSG);
        }

        // поиск пользователя по email + passHash с возратом его id
        List<ArgLine> results = userHandler.selectUser(user.getEmail(), user.getPassHash());

        User result;
        switch (results.size()) {
            case 0: result = null; break;
            case 1: {
                result = convertManager.wrapUser(results.get(0));
                break;
            }
            default: throw new IllegalStateException("Database contains more than one user with email " + user.getEmail() + ". Fix it");
        }
        dbController.commitTransaction();
        return result;
    }



    /**
     * Создает новую беседу, добавляет в нее создателя
     *
     * @param name      название беседы
     * @param creatorId id пользователя, создавшего беседы
     * @return id новой беседы
     * @throws SQLException
     */
    public int createConversation(String name, int creatorId) throws SQLException {
        try {
            // проверка аргументов
            if (!ChatRules.isValidConversationName(name) || !userHandler.checkUser(creatorId)) {
                throw new IllegalArgumentException(ILL_ARGS_MSG);
            }

            // вставка новой беседы, добавление создателя
            int conversId = conversHandler.insertConversation(name, creatorId);
            conversHandler.insertUserToConversation(conversId, creatorId);
            dbController.commitTransaction();
            return conversId;
        } catch (SQLException exc) {
            try {
                dbController.rollbackTransaction();
            } catch (SQLException rbExc) {
                //log.error(SQL_ROLLBACK_EXC_MSG, rbExc);
                System.out.println(SQL_ROLLBACK_EXC_MSG + rbExc);
            }
            throw exc;
        }
    }

    /**
     * Возвращает коллекцию друзей пользователя
     *
     * @param userId пользователь, чей список друзей формирует метод
     * @return коллекция друзей пользователя
     * @throws SQLException
     */
    public List<User> getUserFriends(int userId) throws SQLException {
        // проверка аргументов
        if (!userHandler.checkUser(userId)) {
            throw new IllegalArgumentException(ILL_ARGS_MSG);
        }

        // формирование списка друзей
        List<ArgLine> friends = friendsHandler.selectFriends(userId);
        // оборачивание List<ArgLine> в List<User> и возврат результата
        List<User> results = convertManager.wrapFriends(friends);
        dbController.commitTransaction();
        return results;
    }

    /**
     * Возвращает коллекцию бесед пользователя
     *
     * @param userId пользователь, чей список бесед формирует метод
     * @return коллекция бесед пользователя
     * @throws SQLException
     */
    public List<Conversation> getUserConversations(int userId) throws SQLException {
        // проверка аргументов
        if (!userHandler.checkUser(userId)) {
            throw new IllegalArgumentException(ILL_ARGS_MSG);
        }

        // формирование списка друзей
        List<ArgLine> conversations = conversHandler.selectUserConversations(userId);
        // оборачивание List<ArgLine> в List<Conversation> и возврат результата
        List<Conversation> results = convertManager.wrapConversations(conversations);
        dbController.commitTransaction();
        return results;
    }

    /**
     * Добавляет пользователя к беседе
     *
     * @param conversId   беседа
     * @param userId      добавляемый пользователь
     * @param initiatorId инициатор добавления
     * @throws SQLException
     */
    public void addUserToConversation(int conversId, int userId, int initiatorId) throws SQLException {
        // проверка существования аргументов
        if (!conversHandler.checkConversation(conversId)
                || !userHandler.checkUser(userId)
                || !userHandler.checkUser(initiatorId)) {
            throw new IllegalArgumentException(ILL_ARGS_MSG);
        }
        // проверка условий
        if (!conversHandler.isUserCreateConversation(initiatorId, conversId)
                || conversHandler.isUserInConversation(userId, conversId)) {
            throw new IllegalArgumentException(ILL_ARGS_MSG);
        }

        // вставка нового пользователя userId в conversId
        conversHandler.insertUserToConversation(conversId, userId);
        dbController.commitTransaction();
    }

    /**
     * Исключает пользователя из беседы
     *
     * @param conversId   беседа, из которой треюуется исключить участника
     * @param memberId    исключаемый участник
     * @param initiatorId пользователь, инициирующий исключение
     * @throws SQLException
     */
    public void removeMemberFromConversation(int conversId, int memberId, int initiatorId) throws SQLException {
        // проверка существования аргументов
        if (!conversHandler.checkConversation(conversId)
                || !userHandler.checkUser(memberId)
                || !userHandler.checkUser(initiatorId)) {
            throw new IllegalArgumentException(ILL_ARGS_MSG);
        }
        // проверка условий
        if ((memberId == initiatorId)
                || !conversHandler.isUserCreateConversation(conversId, initiatorId)
                || !conversHandler.isUserInConversation(memberId, conversId)) {
            throw new IllegalArgumentException(ILL_ARGS_MSG);
        }

        // удаление пользователя memberId из conversId
        conversHandler.deleteMemberFromConversation(conversId, memberId);
        dbController.commitTransaction();
    }

    /**
     * Удаляет существующую беседу, удаляет всех участников и их сообщения
     *
     * @param conversId   удаляемая беседа
     * @param initiatorId пользователь, инициирующий удаление
     * @throws SQLException
     */
    public void removeConversation(int conversId, int initiatorId) throws SQLException {
        try {
            // проверка аргументов
            if (!conversHandler.checkConversation(conversId)
                    || !userHandler.checkUser(initiatorId)
                    || !conversHandler.isUserCreateConversation(initiatorId, conversId)) {
                throw new IllegalArgumentException(ILL_ARGS_MSG);
            }

            // удаление конференции
            msgHandler.deleteAllConversationMessages(conversId);
            conversHandler.deleteAllMembersFromConversation(conversId);
            conversHandler.deleteConversation(conversId);
            dbController.commitTransaction();
        } catch (SQLException exc) {
            try {
                dbController.rollbackTransaction();
            } catch (SQLException rbExc) {
                //log.error(SQL_ROLLBACK_EXC_MSG, rbExc);
                System.out.println(SQL_ROLLBACK_EXC_MSG + rbExc);
            }
            throw exc;
        }
    }

    /**
     * Сохраняет отправленное сообщение, делая его доступным остальным собеседникам
     *
     * @param text      текст сообщения
     * @param conversId беседа, в которую отправляется сообщение
     * @param senderId  отправитель
     * @throws SQLException
     */
    public void sendMessage(String text, int conversId, int senderId) throws SQLException {
        // проверка аргументов
        if (!ChatRules.isValidMessageText(text)
                || !conversHandler.checkConversation(conversId)
                || !userHandler.checkUser(senderId)
                || !conversHandler.isUserInConversation(senderId, conversId)) {
            throw new IllegalArgumentException(ILL_ARGS_MSG);
        }

        // вставка сбщ
        msgHandler.insertMessage(text, conversId, senderId);
        dbController.commitTransaction();
    }

    /**
     * Возвращает коллекцию последних сообщений беседы
     *
     * @param conversId   беседа, чьи сообщения требуются
     * @param initiatorId пользователь, запрашавающий сообщения
     * @return коллекция сообщений беседы
     * @throws SQLException
     */
    public List<Message> getMessages(int conversId, int initiatorId) throws SQLException {
        // проверка аргументов
        if (!conversHandler.checkConversation(conversId)
                || !userHandler.checkUser(initiatorId)
                || !conversHandler.isUserInConversation(initiatorId, conversId)) {
            throw new IllegalArgumentException(ILL_ARGS_MSG);
        }

        // выбор сообщений из бд
        List<ArgLine> messages = msgHandler.selectMessages(conversId);
        List<Message> results = convertManager.wrapMessages(messages);
        dbController.commitTransaction();
        return results;
    }

    /**
     * Добавляет статус "друзей" у двух пользователей
     *
     * @param initiatorId пользователь-инициатор
     * @param friendId    другой пользователь
     * @throws SQLException
     */
    public void addFriend(int initiatorId, int friendId) throws SQLException {
        // проверка аргументов
        if (initiatorId == friendId
                || !userHandler.checkUser(initiatorId)
                || !userHandler.checkUser(friendId)
                || friendsHandler.areUsersFrends(initiatorId, friendId)) {
            throw new IllegalArgumentException(ILL_ARGS_MSG);
        }

        // вставка пары друзей
        friendsHandler.insertFriendPair(initiatorId, friendId);
        dbController.commitTransaction();
    }

    /**
     * Добавляет статус "друзей" у двух пользователей
     *
     * @param initiatorId пользователь-инициатор
     * @param friendEmail эл. почта другого пользователя
     * @throws SQLException
     */
    public void addFriend(int initiatorId, String friendEmail) throws SQLException {
        if(!ChatRules.isValidUserEmail(friendEmail)) {
            throw new IllegalArgumentException(ILL_ARGS_MSG);
        }

        addFriend(initiatorId, userHandler.findUser(friendEmail));
        dbController.commitTransaction();
    }

    /**
     * Удаляет статус "друзей" у двух пользователей
     *
     * @param initiatorId пользователь-инициатор разрыва
     * @param friendId    пользователь, которого исключают из друзей
     * @throws SQLException
     */
    public void removeFriend(int initiatorId, int friendId) throws SQLException {
        // проверка аргументов
        if (initiatorId == friendId
                || !userHandler.checkUser(initiatorId)
                || !userHandler.checkUser(friendId)
                || !friendsHandler.areUsersFrends(initiatorId, friendId)) {
            throw new IllegalArgumentException(ILL_ARGS_MSG);
        }

        // вставка пары друзей
        friendsHandler.deleteFriendPair(initiatorId, friendId);
        dbController.commitTransaction();
    }

    /**
     * Формирует список друзей пользователя userId, которые не являются членами conversId
     *
     * @param userId пользователь, чьих друзей нужно найти
     * @param conversId беседа, в которую не входят друзья
     * @return List<User> список друзей, не являющихся членами conversId
     */
    public List<User> getNonMemberFriends(int userId, int conversId) throws SQLException {
        // проверка аргументов
        if (!userHandler.checkUser(userId)
                || !conversHandler.checkConversation(conversId)) {
            throw new IllegalArgumentException(ILL_ARGS_MSG);
        }

        List<ArgLine> friends = friendsHandler.selectNonMemberFriends(userId, conversId);
        List<User> results = convertManager.wrapFriends(friends);
        dbController.commitTransaction();
        return results;
    }

    public List<User> getConversationMembers(int conversId) throws SQLException {
        if (!conversHandler.checkConversation(conversId)) {
            throw new IllegalArgumentException(ILL_ARGS_MSG);
        }

        List<ArgLine> members = conversHandler.selectConversationMembers(conversId);
        List<User> results = convertManager.wrapUsers(members);
        dbController.commitTransaction();
        return results;
    }
}
