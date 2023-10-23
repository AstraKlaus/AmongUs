package ak.telebot.amongus.models;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "Task")
public class Task {

    @Id
    @Column(name = "id")
    private Long taskId;

    @Column(name = "task")
    private String task;

    @Column(name = "difficulty")
    private String difficulty;

    @ManyToMany(mappedBy = "tasks")
    private List<Lobby> lobbies;
}
