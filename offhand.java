
                bindCrystal.isDown(key) -> switchToType(Type.CRYSTAL)
            }
        }

        safeListener<PacketEvent.Receive> {
            if (it.packet !is SPacketConfirmTransaction || it.packet.windowId != 0 || !transactionLog.containsKey(it.packet.actionNumber)) return@safeListener

            transactionLog[it.packet.actionNumber] = true
            if (!transactionLog.containsValue(false)) {
                confirmTimer.reset(confirmTimeout * -50L) // If all the click packets were accepted then we reset the timer for next moving
            }
        }

        safeListener<TickEvent.ClientTickEvent>(1100) {
            if (player.isDead || player.health <= 0.0f) return@safeListener

            if (!confirmTimer.tick(confirmTimeout.toLong(), false)) return@safeListener
            if (!movingTimer.tick(delay.toLong(), false)) return@safeListener // Delays `delay` ticks

            updateDamage()

            if (!player.inventory.itemStack.isEmpty) { // If player is holding an in inventory
                if (mc.currentScreen is GuiContainer) { // If inventory is open (playing moving item)
                    movingTimer.reset() // reset movingTimer as the user is currently interacting with the inventory.
                } else { // If inventory is not open (ex. inventory desync)
                    removeHoldingItem()
                }
                return@safeListener
            }

            switchToType(getType(), true)
        }
    }

    private fun SafeClientEvent.getType() = when {
        checkTotem() -> Type.TOTEM
        checkStrength() -> Type.STRENGTH
        checkGapple() -> Type.GAPPLE
        checkCrystal() -> Type.CRYSTAL
        player.heldItemOffhand.isEmpty -> Type.TOTEM
        else -> null
    }

    private fun SafeClientEvent.checkTotem() = player.scaledHealth < hpThreshold
        || (checkDamage && player.scaledHealth - maxDamage < hpThreshold)

    private fun SafeClientEvent.checkGapple() = offhandGapple
        && (checkAuraG && CombatManager.isActiveAndTopPriority(KillAura)
        || checkWeaponG && player.heldItemMainhand.item.isWeapon
        || (checkCAGapple && !offhandCrystal) && CombatManager.isOnTopPriority(CrystalAura))

    private fun checkCrystal() = offhandCrystal
        && checkCACrystal && CrystalAura.isEnabled && CombatManager.isOnTopPriority(CrystalAura)

    private fun SafeClientEvent.checkStrength() = offhandStrength
        && !player.isPotionActive(MobEffects.STRENGTH)
        && player.inventoryContainer.inventory.any(Type.STRENGTH.filter)
        && (checkAuraS && CombatManager.isActiveAndTopPriority(KillAura)
        || checkWeaponS && player.heldItemMainhand.item.isWeapon)

    private fun SafeClientEvent.switchToType(typeOriginal: Type?, alternativeType: Boolean = false) {
        // First check for whether player is holding the right item already or not
        if (typeOriginal == null || checkOffhandItem(typeOriginal)) return

        val attempts = if (alternativeType) 4 else 1

        getItemSlot(typeOriginal, attempts)?.let { (slot, typeAlt) ->
            if (slot == player.offhandSlot) return

            transactionLog.clear()
            moveToSlot(slot, player.offhandSlot).forEach {
                transactionLog[it] = false
            }

            playerController.updateController()

            confirmTimer.reset()
            movingTimer.reset()

            if (switchMessage) MessageSendHelper.sendChatMessage("$chatName Offhand now has a ${typeAlt.toString().toLowerCase()}")
        }
    }

    private fun SafeClientEvent.checkOffhandItem(type: Type) = type.filter(player.heldItemOffhand)

    private fun SafeClientEvent.getItemSlot(type: Type, attempts: Int): Pair<Slot, Type>? =
        getSlot(type)?.to(type)
            ?: if (attempts > 1) {
                getItemSlot(type.next(), attempts - 1)
            } else {
                null
            }

    private fun SafeClientEvent.getSlot(type: Type): Slot? {
        return player.offhandSlot.takeIf(filter(type))
            ?: if (priority == Priority.HOTBAR) {
                player.hotbarSlots.findItemByType(type)
                    ?: player.inventorySlots.findItemByType(type)
                    ?: player.craftingSlots.findItemByType(type)
            } else {
                player.inventorySlots.findItemByType(type)
                    ?: player.hotbarSlots.findItemByType(type)
                    ?: player.craftingSlots.findItemByType(type)
            }
    }

    private fun List<Slot>.findItemByType(type: Type) =
        find(filter(type))

    private fun filter(type: Type) = { it: Slot ->
        type.filter(it.stack)
    }

    private fun SafeClientEvent.updateDamage() {
        maxDamage = 0f
        if (!checkDamage) return

        for (entity in world.loadedEntityList) {
            if (entity.name == player.name) continue
            if (entity !is EntityMob && entity !is EntityPlayer && entity !is EntityEnderCrystal) continue
            if (player.getDistance(entity) > 10.0f) continue

            when {
                mob && entity is EntityMob -> {
                    maxDamage = max(calcDamageFromMob(entity), maxDamage)
                }
                this@AutoOffhand.player && entity is EntityPlayer -> {
                    maxDamage = max(calcDamageFromPlayer(entity, true), maxDamage)
                }
                crystal && entity is EntityEnderCrystal -> {
                    val damage = CombatManager.crystalMap[entity] ?: continue
                    maxDamage = max(damage.selfDamage, maxDamage)
                }
            }
        }

        if (falling && nextFallDist > 3.0f) maxDamage = max(ceil(nextFallDist - 3.0f), maxDamage)
    }

    private val SafeClientEvent.nextFallDist get() = player.fallDistance - player.motionY.toFloat()
}