package com.hubsabai.changelog.storage;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.List;

@Entity
@Table(
    name = "changelog_revision",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_changelog_revision", columnNames = {"version_id", "audience", "sequence"}
    )
)
public class ChangelogRevision extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "version_id", nullable = false)
    public ChangelogVersion version;

    @Column(length = 50)
    public String audience;

    public int sequence;

    @Column(columnDefinition = "text")
    public String text;

    @Column(length = 10)
    public String source;

    @Column(length = 255)
    public String model;

    public Integer tokens;

    @Column(name = "duration_ms")
    public Integer durationMs;

    @Column(name = "edited_by", length = 255)
    public String editedBy;

    @Column(name = "created_at")
    public OffsetDateTime createdAt;

    public static List<ChangelogRevision> findByVersion(long versionId) {
        return find("version.id = ?1 order by audience, sequence asc", versionId).list();
    }

    public static List<ChangelogRevision> findByVersionAndAudience(long versionId, String audience) {
        return find("version.id = ?1 and audience = ?2 order by sequence asc", versionId, audience).list();
    }

    public static ChangelogRevision latest(long versionId, String audience) {
        return find("version.id = ?1 and audience = ?2 order by sequence desc", versionId, audience)
                .firstResult();
    }

    public static ChangelogRevision findBySequence(long versionId, String audience, int sequence) {
        return find("version.id = ?1 and audience = ?2 and sequence = ?3", versionId, audience, sequence)
                .firstResult();
    }

    public static int nextSequence(long versionId, String audience) {
        ChangelogRevision last = find("version.id = ?1 and audience = ?2 order by sequence desc",
                versionId, audience).firstResult();
        return last != null ? last.sequence + 1 : 0;
    }
}
