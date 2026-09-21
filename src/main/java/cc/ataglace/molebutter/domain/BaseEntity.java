package cc.ataglace.molebutter.domain;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.github.f4b6a3.tsid.TsidCreator;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

@Getter
@ToString
@EntityListeners(AuditingEntityListener.class) // 이 엔티티가 저장되거나 수정되는 순간을 AuditingEntityListener가 지켜보다가 감사 필드를 자동으로 채우게 해라.
@NoArgsConstructor(access = AccessLevel.PROTECTED) // 매개변수가 없는 생성자를 만들되, 접근 제한자를 protected로 만들어라.
@MappedSuperclass // 이 클래스 자체를 별도의 엔티티로 사용하지는 않지만, 이 클래스에 선언된 JPA 필드는 자식 엔티티가 물려받도록 해라.
public abstract class BaseEntity {

    @Id
    @Column(name = "id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @CreatedDate
    @Column(updatable = false)
    protected LocalDateTime createdAt;

    @LastModifiedDate
    protected LocalDateTime updatedAt;

    @CreatedBy
    @Column(updatable = false)
    protected String createdBy;

    @LastModifiedBy
    protected String updatedBy;

    @PrePersist
    private void prePersist() {
        if (this.id == null) {
            this.id = TsidCreator.getTsid().toLong();
        }
    }
}
