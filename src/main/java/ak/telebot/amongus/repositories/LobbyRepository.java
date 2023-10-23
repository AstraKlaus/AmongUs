package ak.telebot.amongus.repositories;

import ak.telebot.amongus.models.Lobby;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LobbyRepository extends JpaRepository<Lobby, Long> {
    Optional<Lobby> findByName(String name);
    boolean existsByName(String name);
}
