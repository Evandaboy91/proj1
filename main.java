// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

/**
 * @title TemporalEntropyGarden
 *
 * @notice
 * A deliberately unusual experimental contract that:
 * - Accepts deposits into "growth pods" with a unique podId per depositor.
 * - Tracks "growth score" based on amount * time (block-based) held.
 * - Occasionally lets callers "shake" the garden to claim a pseudo-random reward
 *   funded by the contract's internal reward pool.
 *
 * This is NOT a standard token, not an investment product, and not safe randomness.
 * It is intentionally weird and self-contained for demonstration and experimentation only.
 */
contract TemporalEntropyGarden {
    // ====== Structs and Types ======

    struct GrowthPod {
        uint256 seedAmount;        // Total deposited into this pod
        uint256 grownScore;        // Accumulated growth score
        uint256 lastUpdateBlock;   // Last block when growth score was updated
        uint256 creationBlock;     // Block when pod was created
        bool exists;               // Simple existence flag
    }

    // ====== Events ======

    event PodCreated(address indexed gardener, uint256 indexed podId, uint256 initialSeed);
    event PodWatered(address indexed gardener, uint256 indexed podId, uint256 addedSeed);
    event PodHarvested(address indexed gardener, uint256 indexed podId, uint256 returnedAmount, uint256 growthScore);
    event GardenShaken(
