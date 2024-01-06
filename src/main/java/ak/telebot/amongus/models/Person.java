package ak.telebot.amongus.models;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "Person")
public class Person {

    @Id
    @Column(name = "chat_id")
    private Long chatId;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "registered_at")
    private Timestamp registeredAt;

    @Column(name = "is_impostor")
    private boolean isImpostor;

    @Column(name = "discussions")
    private int discussions;

    @Column(name = "is_alive")
    private boolean isAlive;

    @Column(name = "voted_id")
    private Long votedId;

    @ManyToOne()
    @JoinColumn(name = "lobby_id", referencedColumnName = "id")
    private Lobby lobby;

    @OneToMany(mappedBy = "owner")
    List<Lobby> ownersLobby;
}
