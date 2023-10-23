package ak.telebot.amongus.repositories;

import ak.telebot.amongus.models.Lobby;
import ak.telebot.amongus.models.Person;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PersonRepository extends JpaRepository<Person, Long> {
    Optional<List<Person>> findAllByLobby(Lobby lobby);
}
