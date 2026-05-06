private suspend fun checkAndFinishExpresses(
    allExp: List<ExpEntity>, 
    allData: List<DataEntity>
): Int {
    var finishedCount = 0
    
    for (exp in allExp) {
        val expMatches = allData.filter { it.id_exp == exp.id_exp }
        if (expMatches.isEmpty()) {
            continue
        }
        if (exp.sts_all != 1) {
            continue
        }
        
        if (!isExpressFinished(expMatches)) {
            continue
        }
        
        var allHaveScore = true
        for (match in expMatches) {
            if (match.sh == 0 && match.sa == 0) {
                allHaveScore = false
                break
            }
        }
        
        var allWins = false
        if (allHaveScore) {
            allWins = true
            for (match in expMatches) {
                var isWin = false
                when (match.type) {
                    924 -> {
                        isWin = match.sh >= match.sa
                    }
                    927 -> {
                        isWin = match.sh + 1 > match.sa
                    }
                    928 -> {
                        isWin = match.sa + 1 >= match.sh
                    }
                    else -> {
                        isWin = match.sh >= match.sa
                    }
                }
                if (!isWin) {
                    allWins = false
                    break
                }
            }
        }
        
        val newStatus: Int
        if (allWins) {
            newStatus = 2
        } else {
            newStatus = -1
        }
        
        val statusText: String
        if (allWins) {
            statusText = "ВЫИГРЫШ"
        } else {
            statusText = "ПРОИГРЫШ"
        }
        
        Log.d(TAG, "Экспресс #${exp.id_exp} завершен: $statusText")
        
        updateExpressStatus(exp.id, newStatus)
        finishedCount++
    }
    
    return finishedCount
}