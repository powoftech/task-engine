package db

import (
	"database/sql"
	"fmt"
	"log"
	"os"

	_ "github.com/lib/pq" // PostgreSQL driver
)

type Database struct {
	Conn *sql.DB
}

// Connect initializes the connection to PostgreSQL
func Connect() (*Database, error) {
	host := getEnv("DB_HOST", "localhost")
	user := getEnv("DB_USER", "green_user")
	pass := getEnv("DB_PASSWORD", "green_password")
	dbname := getEnv("DB_NAME", "task_engine")

	dsn := fmt.Sprintf("host=%s user=%s password=%s dbname=%s sslmode=disable", host, user, pass, dbname)

	conn, err := sql.Open("postgres", dsn)
	if err != nil {
		return nil, fmt.Errorf("failed to open database: %w", err)
	}

	if err := conn.Ping(); err != nil {
		return nil, fmt.Errorf("failed to ping database: %w", err)
	}

	log.Println("Connected to PostgreSQL successfully")
	return &Database{Conn: conn}, nil
}

// ClaimJob updates the job to PROCESSING.
// This enforces IDEMPOTENCY: if another worker already claimed it, rowsAffected will be 0.
func (db *Database) ClaimJob(jobID string) (bool, error) {
	query := `UPDATE jobs SET status = 'PROCESSING' WHERE id = $1 AND status = 'PENDING'`
	result, err := db.Conn.Exec(query, jobID)
	if err != nil {
		return false, fmt.Errorf("failed to execute claim query for job %s: %w", jobID, err)
	}

	rows, err := result.RowsAffected()
	if err != nil {
		return false, fmt.Errorf("failed to get rows affected for job %s: %w", jobID, err)
	}

	return rows > 0, nil
}

// CompleteJob updates the job to COMPLETED with a simulated result
func (db *Database) CompleteJob(jobID string, resultJSON string) error {
	query := `UPDATE jobs SET status = 'COMPLETED', result = $1 WHERE id = $2`
	_, err := db.Conn.Exec(query, resultJSON, jobID)
	if err != nil {
		return fmt.Errorf("failed to complete job %s: %w", jobID, err)
	}
	return nil
}

func getEnv(key, fallback string) string {
	if value, exists := os.LookupEnv(key); exists {
		return value
	}
	return fallback
}
