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
        address indexed shaker,
        uint256 pseudoRandom,
        uint256 reward,
        uint256 globalEntropyCounter
    );
    event RewardPoolFunded(address indexed from, uint256 amount);
    event ParametersTweaked(
        uint256 newGrowthMultiplier,
        uint256 newMinimumBlocksForReward,
        uint256 newMaxRewardBps
    );

    // ====== Storage ======

    // Owner has limited powers to tweak harmless parameters but cannot withdraw others' deposits.
    address public immutable gardenOverseer;

    // Unique, odd-looking prime-like constant for mixing entropy
    uint256 private constant ENTROPY_SALT = 0x3a7f_19d3_5c01_99ab_7e11_d2c9_4f8b_d7c3;

    // Growth configuration
    uint256 public growthMultiplier;       // Multiplier for growth score, arbitrary scale
    uint256 public minimumBlocksForReward; // Minimum blocks since last shake per address
    uint256 public maxRewardBasisPoints;   // Max reward as basis points of reward pool (1 bp = 0.01%)

    // Global counters
    uint256 public globalPodCounter;
    uint256 public globalEntropyCounter;

    // Reward pool (in wei) owned by contract, separate from user deposit balances
    uint256 public rewardPool;

    // Gardener => list of their podIds
