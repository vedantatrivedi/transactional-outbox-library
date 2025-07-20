package com.outbox.transactional.example;

import com.outbox.transactional.annotation.TransactionalOutbox;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;

/**
 * Example User entity demonstrating the usage of the @TransactionalOutbox annotation.
 * 
 * <p>This entity shows how to:
 * <ul>
 *   <li>Use the @TransactionalOutbox annotation with changed fields tracking</li>
 *   <li>Implement a custom toOutboxPayload() method for custom event payloads</li>
 *   <li>Handle different event types (INSERT vs UPDATE)</li>
 * </ul></p>
 * 
 * @author Outbox Library
 * @since 1.0.0
 */
@Entity
@Table(name = "users")
@TransactionalOutbox(includeChangedFields = true)
public class User {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "email", nullable = false, unique = true)
    private String email;
    
    @Column(name = "first_name", nullable = false)
    private String firstName;
    
    @Column(name = "last_name", nullable = false)
    private String lastName;
    
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    
    @Column(name = "updated_at")
    private Instant updatedAt;
    
    // Constructors
    public User() {
        this.createdAt = Instant.now();
    }
    
    public User(String email, String firstName, String lastName) {
        this();
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getEmail() {
        return email;
    }
    
    public void setEmail(String email) {
        this.email = email;
    }
    
    public String getFirstName() {
        return firstName;
    }
    
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }
    
    public String getLastName() {
        return lastName;
    }
    
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }
    
    public Boolean getIsActive() {
        return isActive;
    }
    
    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public Instant getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    /**
     * Custom payload method for outbox messages.
     * 
     * <p>This method allows you to customize what data is included in the outbox
     * message payload, rather than serializing the entire entity.</p>
     * 
     * @return Map containing the custom payload
     */
    public Map<String, Object> toOutboxPayload() {
        return Map.of(
            "id", this.id,
            "email", this.email,
            "firstName", this.firstName,
            "lastName", this.lastName,
            "isActive", this.isActive,
            "createdAt", this.createdAt.toString(),
            "updatedAt", this.updatedAt != null ? this.updatedAt.toString() : null
        );
    }
    
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
    
    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", email='" + email + '\'' +
                ", firstName='" + firstName + '\'' +
                ", lastName='" + lastName + '\'' +
                ", isActive=" + isActive +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
} 