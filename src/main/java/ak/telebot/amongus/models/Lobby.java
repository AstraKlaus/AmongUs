package ak.telebot.amongus.models;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "Lobby")
public class Lobby {

    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long lobbyId;

    @Column(name = "name")
    private String name;

    @ManyToOne()
    @JoinColumn(name = "owner_id", referencedColumnName = "chat_id")
    private Person owner;

    @Column(name = "number_of_impostors")
    private int numberOfImpostors = 3;

    @Column(name = "number_of_tasks")
    private int numberOfTasks = 6;

    @Column(name = "number_of_emergency_meetings")
    private int numberOfEmergencyMeetings = 1;

    @Column(name = "kill_cooldown")
    private int killCooldown = 120;

    @Column(name = "discussion_time")
    private int discussionTime = 100;

    @OneToMany(mappedBy = "lobby")
    private List<Person> persons;

    @ManyToMany()
    @JoinTable(
            name = "Lobby_Task",
            joinColumns = @JoinColumn(name = "lobby_id"),
            inverseJoinColumns = @JoinColumn(name = "task_id")
    )
    private List<Task> tasks;


}
