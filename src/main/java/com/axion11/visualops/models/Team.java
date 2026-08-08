package com.axion11.visualops.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "teams")
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_name", nullable = false, unique = true)
    private String teamName;

    // Excluded from equals()/hashCode(): User.teams is a Set<Team>, so @Data's generated
    // hashCode() here would call admin.hashCode(), which hashes that Set, which calls back into
    // this Team's hashCode() — infinite mutual recursion (StackOverflowError) the moment any
    // Set<Team> operation (e.g. Set.contains/add) touches a Team with its admin set.
    @EqualsAndHashCode.Exclude
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "admin_id")
    private User admin;
}
